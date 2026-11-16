package com.infinitecanvas.canvas.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 素材出参。第一层字段名和前端 {@code web/src/stores/use-asset-store.ts} 里的
 * {@code AssetBase} 完全一致（id/kind/title/coverUrl/tags/source/note/createdAt/updatedAt/metadata），
 * 第二层 {@code data} 见 {@link AssetDataVO}。
 */
@Schema(description = "素材")
public record AssetVO(

        @Schema(description = "素材 id，字符串形式避免前端精度丢失")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "类型：text / image / video / audio")
        String kind,

        @Schema(description = "标题")
        String title,

        @Schema(description = "封面地址")
        String coverUrl,

        @Schema(description = "标签数组")
        List<String> tags,

        @Schema(description = "来源")
        String source,

        @Schema(description = "备注")
        String note,

        @Schema(description = "创建时间")
        LocalDateTime createdAt,

        @Schema(description = "更新时间")
        LocalDateTime updatedAt,

        @Schema(description = "扩展字段，原样透传")
        JsonNode metadata,

        @Schema(description = "内容，结构随 kind 变化")
        AssetDataVO data
) {
}
