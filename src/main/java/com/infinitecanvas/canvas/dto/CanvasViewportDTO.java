package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 画布视口（平移量 + 缩放）。
 * 保存快照时整个对象可选：不传表示"这次不改视口"，前端只是保存节点时不需要传它。
 */
@Schema(description = "画布视口")
public record CanvasViewportDTO(

        @Schema(description = "平移 X", example = "0")
        @NotNull(message = "viewport.x 不能为空")
        Double x,

        @Schema(description = "平移 Y", example = "0")
        @NotNull(message = "viewport.y 不能为空")
        Double y,

        @Schema(description = "缩放比例", example = "1")
        @NotNull(message = "viewport.k 不能为空")
        Double k
) {
}
