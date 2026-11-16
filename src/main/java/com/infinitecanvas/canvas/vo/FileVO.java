package com.infinitecanvas.canvas.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.infinitecanvas.canvas.entity.FileRecord;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 文件出参。字段名对齐前端 {@code web/src/types/image.ts} 里的 ReferenceImage：
 * { id, name, url, storageKey, bytes, width, height, mimeType }。
 *
 * <p>{@code url} 是服务端拼的访问地址（{@code /api/media/a3/a3f9...}），
 * 数据库里只存 {@code storageKey}。这样以后换域名、挂 CDN、迁对象存储，
 * 不需要改一行数据。
 */
@Schema(description = "文件信息")
public record FileVO(

        @Schema(description = "文件 id，字符串形式避免前端精度丢失")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "可直接访问的地址，含 context-path")
        String url,

        @Schema(description = "存储相对路径，导出/迁移时用它定位文件")
        String storageKey,

        @Schema(description = "上传时的文件名")
        String name,

        @Schema(description = "服务端判定的 MIME")
        String mimeType,

        @Schema(description = "扩展名")
        String extension,

        @Schema(description = "字节数")
        Long bytes,

        @Schema(description = "内容摘要，前端可用它做本地缓存键")
        String sha256,

        @Schema(description = "图片宽，非图片为空")
        Integer width,

        @Schema(description = "图片高，非图片为空")
        Integer height,

        @Schema(description = "是否命中秒传：true 表示这次没有真正落盘，直接复用了已有文件")
        boolean deduped,

        @Schema(description = "创建时间")
        LocalDateTime createdAt
) {

    public static FileVO of(FileRecord record, String url, boolean deduped) {
        return new FileVO(
                record.getId(),
                url,
                record.getStorageKey(),
                record.getOriginalName(),
                record.getMimeType(),
                record.getExtension(),
                record.getBytes(),
                record.getSha256(),
                record.getWidth(),
                record.getHeight(),
                deduped,
                record.getCreatedAt()
        );
    }
}
