package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 全量同步汇总。结构对齐前端 {@code PromptSourceRefreshSummary}。
 */
@Schema(description = "全部源同步汇总")
public record PromptRefreshSummaryVO(

        List<PromptRefreshResultVO> results,

        @Schema(description = "所有源同步后的条数之和")
        int total,

        int successCount,

        int failureCount,

        @Schema(description = "跳过的源数量（内容未变化）")
        int skippedCount
) {
}
