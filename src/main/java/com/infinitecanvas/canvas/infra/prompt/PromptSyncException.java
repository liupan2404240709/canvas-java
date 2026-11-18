package com.infinitecanvas.canvas.infra.prompt;

/**
 * 同步过程中的"可预期失败"（上游 404、返回的不是 JSON 数组、内置源返回 0 条…）。
 *
 * <p>单独定义而不是直接抛 {@code BizException}：这类失败**不应该变成 HTTP 错误码**，
 * 而要落到 {@code t_prompt_source.last_error} 上，让前端在源卡片里显示"上次同步为什么失败"。
 * 换句话说，它是"任务结果"，不是"请求非法"。
 */
public class PromptSyncException extends RuntimeException {

    public PromptSyncException(String message) {
        super(message);
    }

    public PromptSyncException(String message, Throwable cause) {
        super(message, cause);
    }
}
