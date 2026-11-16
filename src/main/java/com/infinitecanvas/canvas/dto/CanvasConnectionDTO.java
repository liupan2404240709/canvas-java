package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "画布连线")
public record CanvasConnectionDTO(

        @Schema(description = "前端生成的连线 id，项目内唯一", example = "e_1")
        @NotBlank(message = "连线 id 不能为空")
        @Size(max = 64, message = "连线 id 最长 64 字符")
        String id,

        @Schema(description = "起点节点 id", example = "n_1a2b3c")
        @NotBlank(message = "连线缺少 fromNodeId")
        @Size(max = 64, message = "fromNodeId 最长 64 字符")
        String fromNodeId,

        @Schema(description = "终点节点 id", example = "n_4d5e6f")
        @NotBlank(message = "连线缺少 toNodeId")
        @Size(max = 64, message = "toNodeId 最长 64 字符")
        String toNodeId
) {
}
