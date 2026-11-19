package com.infinitecanvas.canvas.infra.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitecanvas.canvas.infra.net.OutboundHosts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 渠道探测：打一次上游的 {@code /models}，把"通不通"和"有哪些模型"一起回答。
 *
 * <p>把测试连通性和拉模型合并成一个动作，是因为它们在协议上是<b>同一件事</b>——
 * OpenAI 兼容端点没有专门的 ping 接口，{@code GET /models} 就是最轻的那个。
 * 分成两个接口的话，用户点"测试连接"通过、再点"拉取模型"失败，会非常困惑。
 *
 * <p>安全上只做"不复述上游内容"这一件事：错误信息只给状态码和一句人话翻译，
 * 绝不把响应体回显给调用方。原因是渠道地址允许指向内网（本地 Ollama 是正当用法），
 * 一旦回显响应体，这个接口就变成了一个能读内网 HTTP 响应的代理。
 * 用户看到"401"和看到上游的 {@code {"error":"invalid api key"}} 对排查的帮助差别不大，
 * 但后者一旦被滥用代价大得多。
 */
@Component
public class ChannelProbe {

    private static final Logger log = LoggerFactory.getLogger(ChannelProbe.class);

    private static final int BUFFER_SIZE = 16 * 1024;
    /** Gemini 的模型名带 {@code models/} 前缀，落库前要剥掉，否则拼 URL 会变成 /models/models/xxx。 */
    private static final String GEMINI_MODEL_PREFIX = "models/";

    private final RestClient restClient;
    private final ChannelProperties properties;
    private final ObjectMapper objectMapper;

    public ChannelProbe(RestClient channelRestClient, ChannelProperties properties, ObjectMapper objectMapper) {
        this.restClient = channelRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 探测一个渠道配置。
     *
     * @param baseUrl  接口根地址（调用方应已 normalize）
     * @param apiKey   明文 Key；调用方负责先解密
     * @param apiFormat openai / gemini
     * @return 永远不抛异常：失败信息进 {@link Outcome#message()}，由调用方决定是落库还是回给前端
     */
    public Outcome probe(String baseUrl, String apiKey, String apiFormat) {
        URI uri;
        try {
            uri = URI.create(ChannelApiUrls.modelsUrl(baseUrl, apiFormat));
        } catch (RuntimeException e) {
            return Outcome.fail("接口地址不合法：" + e.getMessage(), 0);
        }

        long started = System.nanoTime();
        // 默认 allowPrivateHost=true（本地 Ollama 是正当场景）。只有把开关关掉（服务对外暴露时）
        // 才会走到这里，此时内网/回环地址一律拒绝，理由见 ChannelProperties。
        if (!OutboundHosts.allowed(uri, properties.isAllowPrivateHost())) {
            return withLatency(Outcome.fail("出于安全限制，不允许访问内网/回环地址", 0), started);
        }

        try {
            Outcome outcome = restClient.get()
                    .uri(uri)
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT, "application/json");
                        headers.set(HttpHeaders.USER_AGENT, "canvas-server-channel-probe/1.0");
                        if ("gemini".equalsIgnoreCase(apiFormat)) {
                            // Gemini 用自家的 x-goog-api-key，不是 Bearer；两者混用会被判 401
                            headers.set("x-goog-api-key", apiKey);
                        } else {
                            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
                        }
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status < 200 || status >= 300) {
                            return Outcome.fail(translate(status), status);
                        }
                        String body = new String(readLimited(response.getBody()), StandardCharsets.UTF_8);
                        return parseModels(body, apiFormat);
                    });
            return withLatency(outcome, started);
        } catch (LimitExceededException e) {
            return withLatency(Outcome.fail(e.getMessage(), 0), started);
        } catch (Exception e) {
            // DNS 失败、连接被拒、TLS 握手失败、读超时都在这里。
            // 只取最内层的原因，避免把 "I/O error on GET request for ..." 这种框架噪声丢给用户。
            return withLatency(Outcome.fail("连接失败：" + rootMessage(e), 0), started);
        }
    }

    /**
     * 解析模型列表。同时兼容 OpenAI（{@code {"data":[{"id":"..."}]}}）和
     * Gemini（{@code {"models":[{"name":"models/..."}]}}）两种外壳。
     *
     * <p>解析失败说明这个地址<b>不是</b>一个 OpenAI 兼容端点——最常见的情况是把
     * 控制台首页地址填进来了，那边会返回一坨 HTML，且状态码是 200。
     * 这种情况必须报错，否则用户会以为自己成功了、只是"没有模型"。
     */
    private Outcome parseModels(String body, String apiFormat) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (Exception e) {
            return Outcome.fail("地址返回的不是 JSON，请确认填的是接口地址而不是控制台网页地址", 200);
        }

        JsonNode array = null;
        boolean geminiPrefix = false;
        if (root.isArray()) {
            // 少数自建网关直接返回数组
            array = root;
        } else if ("gemini".equalsIgnoreCase(apiFormat) && root.has("models")) {
            array = root.get("models");
            geminiPrefix = true;
        } else if (root.has("data")) {
            array = root.get("data");
        } else if (root.has("models")) {
            array = root.get("models");
            geminiPrefix = true;
        }
        if (array == null || !array.isArray()) {
            return Outcome.fail("响应里没有模型列表（既没有 data 也没有 models 字段）", 200);
        }

        Set<String> names = new LinkedHashSet<>();
        for (JsonNode node : array) {
            // OpenAI 用 id，Gemini 用 name；有的网关两者都有，先取 id 再退回 name
            String name = text(node, "id");
            if (name.isEmpty()) {
                name = text(node, "name");
            }
            if (geminiPrefix && name.startsWith(GEMINI_MODEL_PREFIX)) {
                name = name.substring(GEMINI_MODEL_PREFIX.length());
            }
            if (!name.isEmpty()) {
                names.add(name);
            }
            if (names.size() >= properties.getMaxModels()) {
                break;
            }
        }

        List<String> models = new ArrayList<>(names);
        if (models.isEmpty()) {
            return new Outcome(true, "连接成功，但该账号下没有可用模型", models, 0, 200);
        }
        return new Outcome(true, "连接成功，发现 " + models.size() + " 个模型", models, 0, 200);
    }

    /** 把 HTTP 状态码翻译成用户能理解的一句话。不带上游响应体，理由见类注释。 */
    private String translate(int status) {
        return switch (status) {
            case 401, 403 -> "鉴权失败（HTTP " + status + "）：API Key 不正确，或该 Key 没有 /models 权限";
            case 404 -> "地址不对（HTTP 404）：这个地址下没有 /models 接口，请检查 Base URL 是否多写或漏写了 /v1";
            case 429 -> "请求过于频繁（HTTP 429）：稍后再试";
            case 500, 502, 503, 504 -> "上游服务异常（HTTP " + status + "），通常与本地配置无关，稍后再试";
            default -> "上游返回 HTTP " + status;
        };
    }

    private Outcome withLatency(Outcome outcome, long startedNanos) {
        int ms = (int) ((System.nanoTime() - startedNanos) / 1_000_000);
        return new Outcome(outcome.ok(), outcome.message(), outcome.models(), ms, outcome.httpStatus());
    }

    private byte[] readLimited(InputStream in) throws IOException {
        long limit = properties.getMaxResponseBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE);
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                // 不能先 readAllBytes 再判断：那时候内存已经被吃掉了
                throw new LimitExceededException("响应体超过 " + limit + " 字节上限，已中断");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        log.debug("渠道探测失败", e);
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 一次探测的结果。
     *
     * @param ok         是否可用（能拿到合法响应）
     * @param message    给人看的一句话；失败时说明原因
     * @param models     上游返回的模型名（已去重、去 Gemini 前缀、截断到上限）
     * @param latencyMs  耗时，前端可以显示"32ms"
     * @param httpStatus 上游状态码，0 表示没连上（DNS/超时/地址非法）
     */
    public record Outcome(boolean ok, String message, List<String> models, int latencyMs, int httpStatus) {

        static Outcome fail(String message, int httpStatus) {
            return new Outcome(false, message, List.of(), 0, httpStatus);
        }
    }

    /**
     * 内部控制流用，不需要暴露给上层。
     *
     * <p>继承 {@link RuntimeException} 而不是 {@code IOException} 是被调用位置逼的：
     * 这段读取发生在 {@code RestClient.exchange(...)} 的 lambda 里，那个函数式接口
     * <b>不允许抛受检异常</b>，所以受检异常既传不出去、catch 也会被判成不可达。
     */
    private static class LimitExceededException extends RuntimeException {
        LimitExceededException(String message) {
            super(message);
        }
    }
}
