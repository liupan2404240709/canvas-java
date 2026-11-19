package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 跨渠道的"可用模型"选项，专供前端下拉框。
 *
 * <p>{@code id} 的格式是 {@code channelKey::modelName}，和原前端的约定逐字一致：
 * 前端把一个字符串存进节点数据，调用时再 split 出渠道和模型。
 * 后端<b>不</b>在这一层做解析校验——它只是个不透明标识，校验发生在真正调用的那一刻。
 */
@Schema(description = "可选模型")
public record ModelOptionVO(

        @Schema(description = "channelKey::modelName", example = "ch_ab12cd34ef56::qwen-image-3.0")
        String id,

        @Schema(description = "所属渠道业务键")
        String channelId,

        @Schema(description = "所属渠道显示名，下拉框可以分组显示")
        String channelName,

        @Schema(description = "上游模型名")
        String name,

        @Schema(description = "image / video / text / audio")
        String capability
) {
}
