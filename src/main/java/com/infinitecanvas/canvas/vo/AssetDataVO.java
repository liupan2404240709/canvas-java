package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 素材内容出参。前端 {@code Asset} 类型是一个按 kind 区分的联合类型：
 *
 * <pre>
 * TextAsset  → data: { content }
 * ImageAsset → data: { dataUrl, storageKey?, width, height, bytes, mimeType }
 * VideoAsset → data: { url, storageKey?, width, height, bytes, mimeType }
 * </pre>
 *
 * <p>Java 侧不为了对齐它拆三个类（那样 Service 里全是 instanceof），
 * 而是用一个"字段全都有、用不上的为 null"的扁平结构。
 * 全局 Jackson 配置了 {@code default-property-inclusion: non_null}，
 * 所以出参里文本素材的响应只有 {@code content}，图片素材只有尺寸那几项，
 * 前端拿到的形状和后端联合类型一致。
 */
@Schema(description = "素材内容，按 kind 取用其中一部分字段")
public record AssetDataVO(

        @Schema(description = "文本内容，kind=text 时有值")
        String content,

        @Schema(description = "访问地址，kind=image/video/audio 时有值。字段名对齐前端 ImageAsset.dataUrl")
        String dataUrl,

        @Schema(description = "存储键")
        String storageKey,

        @Schema(description = "宽")
        Integer width,

        @Schema(description = "高")
        Integer height,

        @Schema(description = "字节数")
        Long bytes,

        @Schema(description = "MIME")
        String mimeType,

        @Schema(description = "音视频时长（毫秒）")
        Integer durationMs
) {
}
