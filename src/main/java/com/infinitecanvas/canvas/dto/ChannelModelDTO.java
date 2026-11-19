package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 渠道下的一个模型，作为渠道整体保存时的一部分传上来。
 *
 * <p>它没有独立的增删接口：模型离开渠道没有意义，单开一套 CRUD 只会让前端多写四个请求，
 * 还要处理"改了模型忘了保存渠道"这类半保存状态。
 */
@Schema(description = "渠道模型")
public record ChannelModelDTO(

        @Schema(description = "上游模型名，如 qwen-image-3.0；空名与重名会被后端跳过", example = "qwen-image-3.0")
        @Size(max = 128, message = "模型名最长 128 字符")
        String name,

        @Schema(description = "能力：image / video / text / audio", example = "image")
        String capability,

        @Schema(description = "是否启用，默认 true")
        Boolean enabled,

        @Schema(description = "自定义请求脚本，可为空")
        @Size(max = 20000, message = "脚本最长 20000 字符")
        String script
) {
}
