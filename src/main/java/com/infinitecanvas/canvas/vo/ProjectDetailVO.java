package com.infinitecanvas.canvas.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 画布详情。打开画布页时一次性把整份数据拉回来，前端直接铺满画布。
 *
 * version 必须返回：前端保存时原样带回来，后端据此判断"我改的是不是最新版本"。
 */
@Schema(description = "画布详情")
public record ProjectDetailVO(

        @Schema(description = "画布 id")
        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        @Schema(description = "标题")
        String title,

        @Schema(description = "当前版本号，保存快照时要带回来")
        Integer version,

        @Schema(description = "背景样式")
        String backgroundMode,

        @Schema(description = "是否显示图片信息")
        Boolean showImageInfo,

        @Schema(description = "视口")
        CanvasViewportVO viewport,

        @Schema(description = "节点列表")
        List<CanvasNodeVO> nodes,

        @Schema(description = "连线列表")
        List<CanvasConnectionVO> connections,

        @Schema(description = "创建时间")
        LocalDateTime createdAt,

        @Schema(description = "更新时间")
        LocalDateTime updatedAt
) {
}
