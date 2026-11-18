package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 单个源的同步结果。
 *
 * <p>为什么同步失败**不**抛 HTTP 错误：这是"任务结果"而不是"接口调用失败"。
 * 接口本身正常返回了，只是这次去上游取数据没取到——前端需要拿到
 * {@code lastError} 原样显示在源卡片上，而不是被全局异常处理器变成一句
 * "服务暂时不可用"。
 */
@Schema(description = "提示词源同步结果")
public record PromptRefreshResultVO(

        String sourceId,

        String sourceName,

        @Schema(description = "同步后的条数（失败时为上次的条数）")
        int count,

        @Schema(description = "最近一次成功时间，ISO 格式")
        String lastSuccessAt,

        @Schema(description = "失败原因，成功时为空串")
        String lastError,

        boolean success,

        @Schema(description = "true 表示内容没变，跳过了写库（命中内容摘要或 304）")
        boolean skipped
) {
}
