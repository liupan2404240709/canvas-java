package com.infinitecanvas.canvas.infra.net;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 出站地址的主机安全校验，提示词同步与渠道探测共用。
 *
 * <p>抽出来的直接原因：两处都要回答"这个域名解析出来的 IP 能不能打"，而这段逻辑
 * <b>写错一次就等于没有防护</b>（只判断字符串前缀会被 {@code 0x7f.0.0.1}、
 * 十进制 IP、指向 127.0.0.1 的域名绕过）。安全代码复制两份意味着以后只修一处。
 *
 * <p>两个调用方的策略相反，所以"允不允许内网"是参数而不是常量：
 * <ul>
 *   <li>提示词源：默认<b>拒绝</b>内网。用户填的是一个第三方 JSON 地址，没有任何理由指向
 *       {@code 192.168.x.x}，拒绝它不损失功能。</li>
 *   <li>渠道：默认<b>允许</b>内网。{@code http://127.0.0.1:11434/v1}（本地 Ollama + vLLM）
 *       是这个功能的核心场景，拒掉等于砍掉自建模型这条路。</li>
 * </ul>
 *
 * <p>局限：这是"请求发出前"的检查，挡不住 DNS rebinding（解析时是公网 IP、真正连接时变内网）。
 * 要做到那种程度得在连接层固定解析结果，属于生产级出站网关的职责。
 */
public final class OutboundHosts {

    private OutboundHosts() {
    }

    /**
     * 主机是否允许访问。{@code allowPrivate=true} 时不做任何限制。
     *
     * <p>注意 {@code UnknownHostException} 返回 {@code false}：解析不出来的域名连不上，
     * 提前拒绝能让错误信息更准确，也避免把它当成"公网地址"放过去。
     */
    public static boolean allowed(URI uri, boolean allowPrivate) {
        if (allowPrivate) {
            return true;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress()
                        || address.isSiteLocalAddress()
                        || address.isLinkLocalAddress()
                        || address.isAnyLocalAddress()
                        || address.isMulticastAddress()) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
