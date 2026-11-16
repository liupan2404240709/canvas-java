package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "画布视口")
public record CanvasViewportVO(Double x, Double y, Double k) {
}
