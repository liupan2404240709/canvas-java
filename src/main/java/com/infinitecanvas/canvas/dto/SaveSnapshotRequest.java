package com.infinitecanvas.canvas.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 保存整份快照。语义是"整份替换"：传进来的 nodes / connections 就是画布当前的全部内容，
 * 后端删掉旧的再写新的，不做差量比对。
 *
 * version 是上次读到的版本号。不传直接拒绝——没有它就没法判断这份快照是基于旧数据改的。
 */
@Schema(description = "保存画布快照请求")
public record SaveSnapshotRequest(

        @Schema(description = "客户端读到的版本号，首次保存传 0", example = "3")
        @NotNull(message = "version 不能为空")
        @Min(value = 0, message = "version 不能为负")
        Integer version,

        @Schema(description = "视口，不传表示保持原值")
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
