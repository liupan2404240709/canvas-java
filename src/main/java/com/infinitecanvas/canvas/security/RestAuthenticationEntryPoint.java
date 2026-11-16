package com.infinitecanvas.canvas.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 未认证时的统一出口。Spring Security 默认返回一个空的 403/401 页面，
 * 前端拿不到 code 就没法区分"没登录"和"没有权限"，所以这里手写 JSON。
 *
 * 业务码优先取 JwtAuthenticationFilter 挂上来的原因（过期 / 无效 / 已撤销），
 * 拿不到再退回通用的 UNAUTHORIZED（压根没带 token 的情况）。
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        Object reason = request.getAttribute(JwtAuthenticationFilter.ATTR_AUTH_ERROR);
        ResultCode resultCode = reason instanceof ResultCode code ? code : ResultCode.UNAUTHORIZED;

        response.setStatus(resultCode.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), Result.fail(resultCode));
    }
}
