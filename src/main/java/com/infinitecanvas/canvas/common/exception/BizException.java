package com.infinitecanvas.canvas.common.exception;

import com.infinitecanvas.canvas.common.api.ResultCode;
import lombok.Getter;

/**
 * 可预期的业务异常。抛出后由 GlobalExceptionHandler 统一转成响应，
 * 调用方不需要在每个 Controller 里写 try/catch。
 */
@Getter
public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }
}
