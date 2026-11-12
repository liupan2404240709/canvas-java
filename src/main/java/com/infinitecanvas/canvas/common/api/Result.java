package com.infinitecanvas.canvas.common.api;

import com.infinitecanvas.canvas.common.constant.TraceId;

/**
 * 统一响应体。成功与失败用同一结构，前端只需要判断 code 是否为 0。
 *
 * @param code    业务码，0 表示成功
 * @param message 提示文案，可直接展示
 * @param data    业务数据，失败时为 null
 * @param traceId 本次请求的链路标识，排查线上问题时让用户直接截图即可
 */
public record Result<T>(int code, String message, T data, String traceId) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data, TraceId.current());
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null, TraceId.current());
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null, TraceId.current());
    }
}
