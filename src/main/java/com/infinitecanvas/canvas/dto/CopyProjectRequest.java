package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "复制画布请求")
public record CopyProjectRequest(

        @Schema(description = "新画布标题，不传则用「原标题 副本」", example = "我的画布 副本")
        @Size(max = 100, message = "画布标题最长 100 字符")
        String title
) {
}
