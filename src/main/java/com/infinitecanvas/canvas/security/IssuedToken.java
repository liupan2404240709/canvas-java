package com.infinitecanvas.canvas.security;

import java.time.Instant;

/**
 * 签发结果。除了 token 字符串本身，还要把 jti 和过期时间带出来：
 * jti 用于写 Redis 白名单 / 黑名单，过期时间用于算黑名单的 TTL。
 */
public record IssuedToken(String token, String jti, Instant expiresAt, long expiresInSeconds) {
}
