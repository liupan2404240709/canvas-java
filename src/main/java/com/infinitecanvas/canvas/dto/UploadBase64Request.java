package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "base64 上传请求。前端画布大量使用 dataURL（截图、粘贴、生成结果），这里省掉一次 File 对象转换")
public record UploadBase64Request(

        @Schema(description = "完整的 dataURL 或裸 base64 串",
                example = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg...")
        String dataUrl,

        @Schema(description = "文件名，只用于展示和下载时的默认名")
        String filename,

        @Schema(description = "业务来源，默认 asset", example = "asset")
        String bizType
) {
}
