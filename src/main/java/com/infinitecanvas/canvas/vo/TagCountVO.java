package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "标签及其使用次数")
public record TagCountVO(

        @Schema(description = "标签名")
        String tag,

        @Schema(description = "被多少条素材使用")
        Integer count
) {
}
