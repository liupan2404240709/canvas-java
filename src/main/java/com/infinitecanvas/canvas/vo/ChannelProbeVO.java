package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 渠道探测结果。
 *
 * <p>故意<b>不</b>抛 HTTP 错误：探测失败是"业务上的一个答案"，不是"请求出错"。
 * 如果连不通就返回 500，前端每个调用点都要 try/catch 才能显示一句"连接失败"；
 * 而这里前端拿到 {@code ok=false} 直接显示 {@code message} 就行。
 */
@Schema(description = "渠道探测结果")
public record ChannelProbeVO(

        @Schema(description = "是否连通")
        boolean ok,

        @Schema(description = "一句话结果；失败时是原因，已翻译成人话")
        String message,

        @Schema(description = "上游返回的模型（已去重）；失败时为空数组")
        List<ModelCandidateVO> models,

        @Schema(description = "耗时毫秒，0 表示没连上")
        int latencyMs,

        @Schema(description = "上游 HTTP 状态码；0 表示没连上（DNS/超时/地址非法）")
        int httpStatus
) {
}
