package com.infinitecanvas.canvas.common.api;

import lombok.Getter;

/**
 * 业务错误码。code 给前端判断分支，httpStatus 给网关 / 浏览器 / 监控识别，
 * 两者分开，避免"业务失败一律返回 200"这种后期很难排查的写法。
 */
@Getter
public enum ResultCode {

    SUCCESS(0, "success", 200),
    BAD_REQUEST(40000, "请求参数有误", 400),
    UNAUTHORIZED(40100, "未登录或登录已过期", 401),
    FORBIDDEN(40300, "没有访问权限", 403),
    NOT_FOUND(40400, "资源不存在", 404),
    CONFLICT(40900, "资源状态冲突", 409),
    TOO_MANY_REQUESTS(42900, "请求过于频繁", 429),
    INTERNAL_ERROR(50000, "服务暂时不可用", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    ResultCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
