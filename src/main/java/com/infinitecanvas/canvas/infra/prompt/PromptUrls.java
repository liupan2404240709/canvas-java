package com.infinitecanvas.canvas.infra.prompt;

import com.infinitecanvas.canvas.infra.net.OutboundHosts;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 出站地址校验。两件事：
 *
 * <ol>
 *   <li><b>协议白名单</b>：只允许 http/https。不拦的话 {@code file:///etc/passwd}、
 *       {@code ftp://} 都会进到 fetch 里，轻则报错重则读本地文件。</li>
 *   <li><b>SSRF 防护</b>：解析域名后逐个检查 IP，回环、内网、链路本地全部拒掉
 *       （{@code allowPrivateHost=false} 时）。只检查字符串前缀是挡不住的——
 *       {@code 0x7f.0.0.1}、十进制 IP、指向 127.0.0.1 的域名都能绕过。</li>
 * </ol>
 */
public final class PromptUrls {

    private PromptUrls() {
    }

    /** 只做语法和协议校验，返回 null 表示不是合法的 http/https 地址。 */
    public static URI parseHttp(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return null;
            }
            return uri;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * 主机是否允许访问。{@code allowPrivate=false} 时拒绝一切非公网地址。
     *
     * <p>实现已抽到 {@link OutboundHosts}——渠道探测要用同一段逻辑，但策略相反
     * （它默认允许内网，因为本地 Ollama 是正当用法）。保留这个门面方法是为了让
     * 阶段 4 的调用点一行都不用改。
     */
    public static boolean hostAllowed(URI uri, boolean allowPrivate) {
        return OutboundHosts.allowed(uri, allowPrivate);
    }

    /**
     * 把源里的相对路径补成绝对地址。
     * registry 里的图片通常是 {@code ./images/xxx.png} 这种相对写法，直接给前端会 404。
     */
    public static String resolve(String baseUrl, String reference) {
        if (reference == null || reference.isBlank()) {
            return "";
        }
        try {
            return URI.create(baseUrl).resolve(reference.trim()).toString();
        } catch (IllegalArgumentException e) {
            return reference.trim();
        }
    }
}
