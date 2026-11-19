package com.infinitecanvas.canvas.infra.channel;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * 渠道地址的归一化与端点拼接。
 *
 * <p>这块看起来只是"拼字符串"，实际上是最容易出隐性 bug 的地方——因为用户的 Base URL
 * 千奇百怪，各家文档给的"根地址"也不统一：
 *
 * <table border="1">
 *   <caption>实测的各家根地址形态</caption>
 *   <tr><th>厂商</th><th>控制台给的 base_url</th><th>拉模型要打</th></tr>
 *   <tr><td>阿里百炼</td><td>https://dashscope.aliyuncs.com/compatible-mode/v1</td><td>.../v1/models</td></tr>
 *   <tr><td>火山方舟</td><td>https://ark.cn-beijing.volces.com/api/v3</td><td>.../api/v3/models</td></tr>
 *   <tr><td>智谱</td><td>https://open.bigmodel.cn/api/paas/v4</td><td>.../v4/models</td></tr>
 *   <tr><td>硅基流动</td><td>https://api.siliconflow.cn/v1</td><td>.../v1/models</td></tr>
 *   <tr><td>DeepSeek</td><td>https://api.deepseek.com</td><td>.../v1/models</td></tr>
 *   <tr><td>本地 Ollama</td><td>http://127.0.0.1:11434/v1</td><td>.../v1/models</td></tr>
 * </table>
 *
 * <p>规则：地址末尾<b>已经带了版本段</b>（{@code /v1}、{@code /v3}、{@code /api/v3}、
 * {@code /paas/v4}、{@code /v1beta} …）就只补 {@code /models}；否则补 {@code /v1/models}。
 * 这样既不用给每家厂商写一个分支，也不会拼出 {@code /v1/v1/models}。
 */
public final class ChannelApiUrls {

    /**
     * 末尾版本段。只认"最后一段"，所以 {@code /api/paas/v4}、{@code /compatible-mode/v1}、
     * {@code /v1beta} 都归一到同一个判断——不需要给每家厂商写一条规则。
     */
    private static final Pattern VERSION_TAIL = Pattern.compile(".*/v\\d+[a-z]*$", Pattern.CASE_INSENSITIVE);

    private ChannelApiUrls() {
    }

    /**
     * 校验并归一化 Base URL：必须是 http/https、必须有主机名、去掉末尾斜杠和锚点。
     * 不合法返回 null，由调用方决定报什么错（这里不抛异常，方便复用）。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            String normalized = uri.toString();
            // 锚点对服务端毫无意义，留着还可能干扰后续拼接
            int hash = normalized.indexOf('#');
            if (hash >= 0) {
                normalized = normalized.substring(0, hash);
            }
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** 拉取上游模型列表的完整 URL。 */
    public static String modelsUrl(String baseUrl, String apiFormat) {
        String base = normalize(baseUrl);
        if (base == null) {
            throw new IllegalArgumentException("baseUrl 不合法: " + baseUrl);
        }
        if ("gemini".equalsIgnoreCase(apiFormat)) {
            // Gemini 的版本段是 v1beta，且模型列表路径就是 /models
            return VERSION_TAIL.matcher(base).matches() ? base + "/models" : base + "/v1beta/models";
        }
        return VERSION_TAIL.matcher(base).matches() ? base + "/models" : base + "/v1/models";
    }

    /** 归一化两个地址是否指向同一端点，用于"重复添加同一渠道"的判断。 */
    public static String dedupeKey(String baseUrl) {
        String base = normalize(baseUrl);
        if (base == null) {
            return "";
        }
        // 大小写不影响主机，但路径大小写敏感，所以只把 scheme + host 转小写
        return base.toLowerCase().replaceAll("/v1$", "").replaceAll("/+$", "");
    }
}
