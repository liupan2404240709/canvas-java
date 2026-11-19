package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 渠道出参。
 *
 * <p>只回 {@code apiKeyHint}（形如 {@code sk-a****wxyz}），<b>任何情况下都不回明文或密文</b>。
 * 这不是"顺手脱敏"，而是接口契约：一旦某个版本开始回明文，前端就会有人拿它去显示、
 * 去缓存、去写进埋点，之后再想收回就收不回了。
 */
@Schema(description = "渠道")
public record ChannelVO(

        @Schema(description = "业务键，等价于前端的 channelId；模型值形如 channelKey::modelName")
        String id,

        String name,

        String baseUrl,

        @Schema(description = "脱敏后的 Key，如 sk-a****wxyz")
        String apiKeyHint,

        @Schema(description = "openai / gemini")
        String apiFormat,

        boolean enabled,

        int sortIndex,

        @Schema(description = "最近一次测试时间，ISO 格式；从未测试为空串")
        String lastTestAt,

        @Schema(description = "最近一次测试是否通过；null = 从未测试")
        Boolean lastTestOk,

        @Schema(description = "最近一次测试失败原因；成功或未测为空串")
        String lastTestError,

        @Schema(description = "旗下模型，按 sortIndex 排序")
        List<ChannelModelVO> models
) {
}
