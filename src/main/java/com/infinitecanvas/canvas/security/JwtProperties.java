package com.infinitecanvas.canvas.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT 相关配置，对应 application.yml 的 app.jwt 段。
 * 用配置类而不是到处 @Value：类型安全、能加校验、IDE 里能跳转。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** HS256 密钥，长度必须 >= 32 字节，否则启动即抛 WeakKeyException。 */
    @NotBlank
    private String secret;

    /** 签发者，解析时会校验，防止别的系统用同一密钥签的 token 混进来。 */
    @NotBlank
    private String issuer = "canvas-server";

    /** access token 有效期，短。 */
    @NotNull
    private Duration accessTokenTtl = Duration.ofMinutes(30);

    /** refresh token 有效期，长，同时作为 Redis 白名单 key 的 TTL。 */
    @NotNull
    private Duration refreshTokenTtl = Duration.ofDays(14);
}
