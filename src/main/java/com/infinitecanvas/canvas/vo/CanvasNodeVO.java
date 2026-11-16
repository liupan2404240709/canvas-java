package com.infinitecanvas.canvas.vo;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 节点出参。字段和前端 CanvasNodeData 完全一致：
 * 前端拿到后直接 setNodes(data.nodes)，不需要再转换一次坐标结构。
 *
 * 单独建 VO 而不是复用 CanvasNodeDTO：入参上挂着一堆校验注解，出参不需要；
 * 而且两者的演化方向不同（入参会收紧校验，出参会裁剪字段）。
 */
@Schema(description = "画布节点")
public record CanvasNodeVO(

        String id,
        String type,
        String title,
        Position position,
        Double width,
        Double height,
        JsonNode metadata
) {

    @Schema(description = "节点坐标")
    public record Position(Double x, Double y) {
    }
}
