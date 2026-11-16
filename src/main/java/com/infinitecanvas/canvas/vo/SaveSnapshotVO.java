package com.infinitecanvas.canvas.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 保存快照的返回值。刻意不回整份快照：几 MB 的数据在保存成功后没有任何用处，
 * 前端只需要把 version 更新成新值，下次保存接着用。
 */
@Schema(description = "保存快照结果")
public record SaveSnapshotVO(

        @Schema(description = "画布 id")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "保存后的新版本号，前端要覆盖本地 version")
        Integer version,

        @Schema(description = "节点数")
        Integer nodeCount,

        @Schema(description = "连线数")
        Integer connectionCount,

        @Schema(description = "保存时间")
        LocalDateTime updatedAt
) {
}
