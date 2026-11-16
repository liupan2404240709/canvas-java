package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

/**
 * 局部更新请求。字段为 null 表示"这次不改"，所以这里一个 @NotNull 都不能加，
 * 否则 PATCH 语义就退化成"必须全量提交"。
 *
 * backgroundMode 刻意不做枚举校验：它对应前端 canvasThemes 里的值，
 * 以后加主题不应该让后端跟着改代码，只校验长度。
 */
@Schema(description = "更新画布请求，只传需要改的字段")
public record UpdateProjectRequest(

        @Schema(description = "新标题，不传表示不改", example = "重命名后的画布")
        @Size(max = 100, message = "画布标题最长 100 字符")
        String title,

        @Schema(description = "背景样式，不传表示不改", example = "dots")
        @Size(max = 32, message = "backgroundMode 最长 32 字符")
        String backgroundMode,

        @Schema(description = "是否显示图片信息，不传表示不改", example = "true")
        Boolean showImageInfo,

        @Schema(description = "视口，不传表示不改")
        @Valid
        CanvasViewportDTO viewport
) {

    public boolean empty() {
        return title == null && backgroundMode == null && showImageInfo == null && viewport == null;
    }
}
