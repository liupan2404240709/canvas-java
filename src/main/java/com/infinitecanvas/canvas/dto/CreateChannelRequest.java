package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 新建渠道。
 *
 * <p>两处和"直觉"不一样的地方：
 * <ul>
 *   <li>{@code channelKey} 不由客户端传。它是前端模型值 {@code channelKey::modelName} 的
 *       前半截，也是 URL 里的路径段——让客户端命名等于把唯一性和格式校验全推给前端。</li>
 *   <li>{@code apiKey} 是<b>必填</b>。没有 Key 的渠道拉不到模型、也生成不了图，
 *       允许建一条空 Key 的渠道只会让列表里多一行永远调不通的配置。</li>
 * </ul>
 */
@Schema(description = "新建渠道请求")
public record CreateChannelRequest(

        @Schema(description = "显示名，可为空；为空时自动取地址里的主机名", example = "阿里云百炼")
        @Size(max = 100, message = "渠道名最长 100 字符")
        String name,

        // 不用 @NotBlank：空地址和"填了但不是 http/https"应该报同一个 40050，
        // 交给 ChannelApiUrls.normalize 统一判断；用注解会变成笼统的 40000
        @Schema(description = "接口根地址，带不带 /v1 都可以", example = "https://dashscope.aliyuncs.com/compatible-mode/v1")
        @Size(max = 512, message = "接口地址最长 512 字符")
        String baseUrl,

        // 同上：空 Key 由 requireApiKey 报 40051，前端才能精确高亮 Key 输入框
        @Schema(description = "API Key 明文，落库前加密", example = "sk-xxxxxxxx")
        @Size(max = 512, message = "API Key 最长 512 字符")
        String apiKey,

        @Schema(description = "接口协议：openai（默认）/ gemini", example = "openai")
        String apiFormat,

        @Schema(description = "是否启用，默认 true")
        Boolean enabled,

        @Schema(description = "随渠道一起创建的模型，可为空")
        List<ChannelModelDTO> models
) {
}
