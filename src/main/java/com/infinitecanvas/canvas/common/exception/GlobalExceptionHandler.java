package com.infinitecanvas.canvas.common.exception;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.api.ResultCode;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常出口。这里只做三件事：翻译成统一结构、选对 HTTP 状态码、留下可排查的日志。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务可预期异常，用 warn 级别，避免污染错误日志。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException e) {
        log.warn("业务异常 code={} message={}", e.getResultCode().getCode(), e.getMessage());
        return build(e.getResultCode(), e.getMessage());
    }

    /** @Valid 校验失败，把字段名和原因拼出来，前端可直接提示。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleInvalidArgument(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + messageOf(error))
                .collect(Collectors.joining("; "));
        return build(ResultCode.BAD_REQUEST, message.isBlank() ? ResultCode.BAD_REQUEST.getMessage() : message);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Void>> handleConstraint(ConstraintViolationException e) {
        return build(ResultCode.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败: {}", e.getMessage());
        return build(ResultCode.BAD_REQUEST, "请求体格式错误");
    }

    /** 路径变量类型不对，比如 /v1/projects/abc。不处理会掉进兜底变 500，看起来像服务挂了。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return build(ResultCode.BAD_REQUEST, "参数格式错误: " + e.getName());
    }

    /**
     * 方法不支持。典型场景：把 POST 打到一个只注册了 GET 的路径上
     * （例如漏写 {@code @PostMapping("/upload")} 的前缀时，
     * {@code POST /v1/files/upload} 会匹配到 {@code GET /v1/files/{id}} 的模式上）。
     * 不单独处理的话会被兜底成 500，排查时容易误判为服务端崩溃。
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}，该路径支持 {}", e.getMethod(), e.getSupportedMethods());
        return build(ResultCode.METHOD_NOT_ALLOWED,
                "请求方法不支持: " + e.getMethod() + "（该路径只接受 "
                        + String.join("/", e.getSupportedMethods() == null ? new String[]{"?"} : e.getSupportedMethods())
                        + "）");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<Void>> handleNoResource(NoResourceFoundException e) {
        String path = e.getResourcePath();
        return build(ResultCode.NOT_FOUND, "接口不存在: " + (path.startsWith("/") ? path : "/" + path));
    }

    /**
     * 上传超过容器上限（spring.servlet.multipart.max-*）。
     *
     * <p>不处理会掉进兜底变 500，前端只能提示"服务器错误"，用户根本不知道是文件太大。
     * 应用层的业务上限（app.storage.*）在 FileServiceImpl 里先卡一次，
     * 走到这里说明连容器那关都没过。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Void>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.warn("上传超过容器上限: {}", e.getMessage());
        return build(ResultCode.FILE_TOO_LARGE, "上传文件过大，请压缩后重试");
    }

    /** 兜底：未预料到的异常只回通用文案，细节留在日志里，避免把堆栈暴露给调用方。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception e) {
        log.error("未处理异常", e);
        return build(ResultCode.INTERNAL_ERROR, ResultCode.INTERNAL_ERROR.getMessage());
    }

    private ResponseEntity<Result<Void>> build(ResultCode resultCode, String message) {
        return ResponseEntity.status(resultCode.getHttpStatus())
                .body(Result.<Void>fail(resultCode, message));
    }

    private String messageOf(FieldError error) {
        return error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage();
    }
}
