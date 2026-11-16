package com.infinitecanvas.canvas.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.infinitecanvas.canvas.entity.CanvasProject;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 画布列表项。只给出列表页真正要显示的字段，不带节点数组——
 * 一个画布几百个节点，列表页 20 条就会把响应撑到几 MB。
 */
@Schema(description = "画布列表项")
public record ProjectSummaryVO(

        @Schema(description = "画布 id，字符串形式避免前端精度丢失")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "标题")
        String title,

        @Schema(description = "节点数")
        Integer nodeCount,

        @Schema(description = "连线数")
        Integer connectionCount,

        @Schema(description = "背景样式")
        String backgroundMode,

        @Schema(description = "创建时间")
        LocalDateTime createdAt,

        @Schema(description = "最后更新时间，列表按它倒序")
        LocalDateTime updatedAt
) {

    public static ProjectSummaryVO from(CanvasProject project) {
        if (project == null) {
            return null;
        }
        return new ProjectSummaryVO(
                project.getId(),
                project.getTitle(),
                project.getNodeCount(),
                project.getConnectionCount(),
                project.getBackgroundMode(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
