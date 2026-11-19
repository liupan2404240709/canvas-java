package com.infinitecanvas.canvas.infra.channel;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 渠道连通性检测的配置。
 *
 * <p>超时比提示词同步（5s/20s）更短：这是<b>用户点一下要等结果</b>的交互式请求，
 * 让他在页面上等 20 秒还不知道成不成，体验比"直接告诉我不通"差得多。
 */
@Data
@ConfigurationProperties(prefix = "app.channel")
public class ChannelProperties {

    private Duration connectTimeout = Duration.ofSeconds(5);

    private Duration readTimeout = Duration.ofSeconds(15);

    /** 模型列表响应体上限。{@code /models} 正常就几十 KB，1MB 已经极宽松。 */
    private int maxResponseBytes = 1024 * 1024;

    /**
     * 是否允许渠道地址指向内网/回环。
     *
     * <p>这里和提示词源（默认也应该关，但本地要开）<b>不一样</b>：渠道地址填
     * {@code http://127.0.0.1:11434/v1}（本地 Ollama）是完全正当的用法，是这个功能的核心场景之一，
     * 所以默认必须为 true。
     *
     * <p>那 SSRF 风险怎么办？关键在于<b>响应的可见性</b>：渠道测试只把"模型名字列表"回显给
     * 填地址的那个人，不返回原始响应体、不跟随重定向到别的地址、错误信息里也只保留状态码。
     * 攻击者最多能验证"某个内网端口上有没有一个 OpenAI 兼容的 /models 接口"，
     * 拿不到响应内容，探测价值被压到很低。真要防死，只能做域名白名单——那本地模型也就没了。
     */
    private boolean allowPrivateHost = true;

    /** 单次最多返回多少个模型名，防止上游返回几万条把前端下拉框撑爆。 */
    private int maxModels = 500;
}
