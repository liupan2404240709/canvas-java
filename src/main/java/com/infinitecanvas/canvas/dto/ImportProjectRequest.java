package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 导入画布。请求体就是「导出文件」的内容，字段名必须和 ProjectExportVO 一致。
 *
 * 文件里的 formatVersion 会比当前支持的版本新，这里要能识别并拒绝，
 * 而不是"读不懂也硬读"——那样会静默丢数据。
 */
@Schema(description = "导入画布请求，结构与导出文件一致")
public record ImportProjectRequest(

        @Schema(description = "来源标识，导出文件里是 infinite-canvas", example = "infinite-canvas")
        String app,

        @Schema(description = "导出格式版本", example = "1")
        Integer formatVersion,

        @Schema(description = "画布标题，不传则用「导入的画布」")
        @Size(max = 100, message = "画布标题最长 100 字符")
        String title,

        @Schema(description = "背景样式")
        @Size(max = 32, message = "backgroundMode 最长 32 字符")
        String backgroundMode,

        @Schema(description = "是否显示图片信息")
        Boolean showImageInfo,

        @Schema(description = "视口")
        @Valid
        CanvasViewportDTO viewport,

        @Schema(description = "全部节点")
        @Valid
        @Size(max = 500, message = "单个画布最多 500 个节点")
        List<CanvasNodeDTO> nodes,

        @Schema(description = "全部连线")
        @Valid
        @Size(max = 2000, message = "单个画布最多 2000 条连线")
        List<CanvasConnectionDTO> connections
) {
}
