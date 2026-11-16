package com.infinitecanvas.canvas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 已登录但权限不足时的出口（403）。阶段 1 所有接口都只要求"已登录"，
 * 暂时触发不到这里；等阶段 2 加了"只能改自己的画布"这类规则后就会用到。
 * 现在就写好的理由是：一旦启用方法级鉴权，缺这个处理器会退化成默认错误页。
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        ResultCode resultCode = ResultCode.FORBIDDEN;
        response.setStatus(resultCode.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Result.fail(resultCode));
    }
}
