package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "创建画布请求")
public record CreateProjectRequest(

        @Schema(description = "画布标题", example = "我的第一张画布")
        @NotBlank(message = "画布标题不能为空")
        @Size(max = 100, message = "画布标题最长 100 字符")
        String title
) {
}
