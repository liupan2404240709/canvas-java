package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 探测一个渠道配置（不落库）。
 *
 * <p>存在的理由：渠道编辑抽屉里用户填完地址和 Key，得先确认能用再保存。
 * 如果只能"保存后测试"，填错一次就要删一次渠道，体验很差。
 *
 * <p>{@code apiKey} 允许为空——此时配合 {@code channelKey} 使用库里已存的密文，
 * 这样"测试已有渠道"不需要前端把 Key 明文拿在手上（它也拿不到，接口只回 hint）。
 */
@Schema(description = "渠道探测请求（不保存）")
public record ChannelProbeRequest(

        @Schema(description = "已存在的渠道业务键；填了且 apiKey 为空时，用库里保存的 Key")
        String channelKey,

        @Schema(description = "接口根地址", example = "https://api.siliconflow.cn/v1")
        @Size(max = 512, message = "接口地址最长 512 字符")
        String baseUrl,

        @Schema(description = "API Key 明文；留空表示用 channelKey 对应的已存 Key")
        @Size(max = 512, message = "API Key 最长 512 字符")
        String apiKey,

        @Schema(description = "接口协议：openai / gemini", example = "openai")
        String apiFormat
) {
}
