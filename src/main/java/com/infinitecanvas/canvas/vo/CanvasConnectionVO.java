package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "画布连线")
public record CanvasConnectionVO(String id, String fromNodeId, String toNodeId) {
}
