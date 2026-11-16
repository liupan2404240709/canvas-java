package com.infinitecanvas.canvas.dto;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 画布节点的传输结构。字段名严格对齐前端 web/src/types/canvas.ts 的 CanvasNodeData，
 * 前端对接时不需要写一层字段映射。
 *
 * metadata 用 JsonNode 原样透传：它的结构由节点类型决定（图片节点有 images，文本节点有 fontSize，
 * 视频节点有 seconds……），后端不该知道也不该校验它的内部字段，否则前端一加新属性就要改后端。
 */
@Schema(description = "画布节点")
public record CanvasNodeDTO(

        @Schema(description = "前端生成的节点 id，项目内唯一", example = "n_1a2b3c")
        @NotBlank(message = "节点 id 不能为空")
        @Size(max = 64, message = "节点 id 最长 64 字符")
        String id,

        @Schema(description = "节点类型，插件节点形如 pluginId:name", example = "image")
        @NotBlank(message = "节点类型不能为空")
        @Size(max = 64, message = "节点类型最长 64 字符")
        String type,

        @Schema(description = "节点标题", example = "第一张图")
        @Size(max = 200, message = "节点标题最长 200 字符")
        String title,

        @Schema(description = "画布坐标与尺寸")
        @NotNull(message = "节点缺少 position")
        @Valid
        Position position,

        @Schema(description = "节点宽度", example = "320")
        @NotNull(message = "节点缺少 width")
        @PositiveOrZero(message = "节点宽度不能为负")
        Double width,

        @Schema(description = "节点高度", example = "320")
        @NotNull(message = "节点缺少 height")
        @PositiveOrZero(message = "节点高度不能为负")
        Double height,

        @Schema(description = "节点扩展数据，结构随类型变化，后端原样保存")
        JsonNode metadata
) {

    @Schema(description = "节点坐标")
    public record Position(

            @Schema(description = "X", example = "120")
            @NotNull(message = "position.x 不能为空")
            Double x,

            @Schema(description = "Y", example = "80")
            @NotNull(message = "position.y 不能为空")
            Double y
    ) {
    }
}
