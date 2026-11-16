package com.infinitecanvas.canvas.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 刷新入参。refreshToken 放在请求体而不是 Header：
 * 它只会被 /auth/refresh 用到，放 Header 反而容易被日志、网关误采集。
 */
public record RefreshTokenRequest(

        @NotBlank(message = "refreshToken 不能为空")
        String refreshToken
) {
}
