package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出格式。这是对外承诺的数据契约，一旦发出去就不能随意改字段名，
 * 所以刻意不复用 ProjectDetailVO——内部 VO 加字段不该影响导出文件。
 *
 * app + formatVersion 两个字段是给导入方判断"这是谁的文件、我能不能读"。
 */
@Schema(description = "画布导出文件")
public record ProjectExportVO(

        @Schema(description = "固定为 infinite-canvas", example = "infinite-canvas")
        String app,

        @Schema(description = "导出格式版本，当前为 1", example = "1")
        Integer formatVersion,

        @Schema(description = "导出时间")
        LocalDateTime exportedAt,

        @Schema(description = "画布标题")
        String title,

        @Schema(description = "背景样式")
        String backgroundMode,

        @Schema(description = "是否显示图片信息")
        Boolean showImageInfo,

        @Schema(description = "视口")
        CanvasViewportVO viewport,

        @Schema(description = "节点列表")
        List<CanvasNodeVO> nodes,

        @Schema(description = "连线列表")
        List<CanvasConnectionVO> connections
) {

    public static final String APP_NAME = "infinite-canvas";

    public static final int FORMAT_VERSION = 1;
}
