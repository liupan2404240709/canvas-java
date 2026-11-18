package com.infinitecanvas.canvas.infra.prompt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * 提示词同步配置。
 *
 * <p>{@code allowPrivateHost} 是**安全开关**：允许出站请求打到内网 / 回环地址。
 * 本地开发必须为 true（e2e 用 {@code http://127.0.0.1:8081/api/demo/...} 下的样例 JSON
 * 做同步测试），但只要这个服务对公网开放，就必须改成 false——否则任何人加一个源，
 * 就能让服务器替他去探测内网（SSRF），比如 {@code http://169.254.169.254/latest/meta-data/}。
 */
@Data
@ConfigurationProperties(prefix = "app.prompt")
public class PromptProperties {

    /** 建连超时。上游是 GitHub raw，国内网络建连可能慢，但超过 5 秒基本就是不通。 */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /** 读超时。整份 JSON 几 MB，20 秒足够。 */
    private Duration readTimeout = Duration.ofSeconds(20);

    /**
     * 响应体上限。必须显式限制：给一个恶意或坏掉的 URL，几十 GB 的响应会把堆撑爆。
     * 8MB 对提示词 JSON 来说非常宽松（实测几个 registry 都在 1MB 以内）。
     */
    private DataSize maxResponseSize = DataSize.ofMegabytes(8);

    /** 标签云缓存时长。同步成功会通过版本号让旧缓存立即失效，所以这里可以放长一点。 */
    private Duration tagCacheTtl = Duration.ofMinutes(5);

    /** 标签云最多返回多少个标签，避免脏数据把整个左侧栏撑爆。 */
    private int tagFacetLimit = 100;

    /** 单次调度最多处理多少个到期源。防止刚启动时一口气把所有源都拉一遍。 */
    private int scheduleBatch = 20;

    /**
     * 是否允许请求内网地址。**生产必须 false**。
     * 默认 true 只是为了让本地开发和 e2e 能用本机的样例 JSON。
     */
    private boolean allowPrivateHost = true;
}
