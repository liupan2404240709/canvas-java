package com.infinitecanvas.canvas.infra.prompt;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 拉取上游 JSON。只负责"把字节取回来"，解析交给 {@link PromptJsonParser}。
 *
 * <p>三个必须做的防护，少一个都会在生产上出事：
 * <ol>
 *   <li><b>超时</b>：不设超时的话，一个不响应的域名会把 Tomcat 线程一直占着，
 *       几个源就能把线程池耗光。建连 5s / 读 20s。</li>
 *   <li><b>响应体上限</b>：流式读取时累计字节数，超过 8MB 立刻中断。
 *       不能先 {@code readAllBytes()} 再判断大小——那时候内存已经吃掉了。</li>
 *   <li><b>协议 + 内网地址校验</b>：见 {@link PromptUrls}。</li>
 * </ol>
 *
 * <p>条件请求（ETag / If-Modified-Since）也在这里做：能拿到 304 就不用把整份 JSON
 * 传回来，对上游流量也更友好。
 */
@Component
public class PromptFetcher {

    private static final int BUFFER_SIZE = 16 * 1024;

    private final RestClient restClient;
    private final PromptProperties properties;

    public PromptFetcher(RestClient promptRestClient, PromptProperties properties) {
        this.restClient = promptRestClient;
        this.properties = properties;
    }

    public FetchResult fetch(String url, String etag, String lastModified) {
        URI uri = PromptUrls.parseHttp(url);
        if (uri == null) {
            throw new PromptSyncException("源地址不是合法的 http/https 地址");
        }
        if (!PromptUrls.hostAllowed(uri, properties.isAllowPrivateHost())) {
            throw new PromptSyncException("出于安全限制，不允许访问该地址（内网/回环地址已禁用）");
        }

        try {
            return restClient.get()
                    .uri(uri)
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT, "application/json, */*;q=0.8");
                        // 带上 User-Agent：部分 CDN 会拒绝空 UA 的请求
                        headers.set(HttpHeaders.USER_AGENT, "canvas-server-prompt-sync/1.0");
                        if (StringUtils.hasText(etag)) {
                            headers.set(HttpHeaders.IF_NONE_MATCH, etag);
                        }
                        if (StringUtils.hasText(lastModified)) {
                            headers.set(HttpHeaders.IF_MODIFIED_SINCE, lastModified);
                        }
                    })
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status == 304) {
                            return new FetchResult("", etag, lastModified, true);
                        }
                        if (status < 200 || status >= 300) {
                            throw new PromptSyncException("上游返回 HTTP " + status);
                        }
                        byte[] body = readLimited(response.getBody());
                        return new FetchResult(
                                new String(body, StandardCharsets.UTF_8),
                                response.getHeaders().getETag(),
                                response.getHeaders().getFirst(HttpHeaders.LAST_MODIFIED),
                                false);
                    });
        } catch (PromptSyncException e) {
            throw e;
        } catch (Exception e) {
            // 连接超时、DNS 失败、TLS 握手失败都落在这里。对外只暴露一句可读的话，
            // 具体堆栈写日志（上层会记 lastError，但那是给用户看的，不该带 Java 类名）。
            throw new PromptSyncException("请求失败：" + rootMessage(e), e);
        }
    }

    private byte[] readLimited(InputStream in) throws IOException {
        long limit = properties.getMaxResponseSize().toBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE);
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new PromptSyncException("响应体超过上限（" + properties.getMaxResponseSize() + "），已中断");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private String rootMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 一次拉取的结果。
     *
     * @param body         响应体，304 时为空串
     * @param etag         响应头里的 ETag，用于下次条件请求
     * @param lastModified 响应头里的 Last-Modified 原文
     * @param notModified  是否命中 304
     */
    public record FetchResult(String body, String etag, String lastModified, boolean notModified) {
    }
}
