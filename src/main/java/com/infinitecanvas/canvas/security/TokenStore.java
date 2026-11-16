package com.infinitecanvas.canvas.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 令牌状态存储。JWT 是无状态的，服务端本来记不住"发过什么、撤销了什么"，
 * 这里用 Redis 补上这两件事：
 *
 * 1. refresh 白名单：key = auth:refresh:{jti}，value = userId。
 *    刷新时必须能在白名单里找到，否则说明被撤销过（例如已登出、或被盗用后重放）。
 * 2. access 黑名单：key = auth:blacklist:{jti}，登出后把还没过期的 access token 放进来，
 *    TTL 设为它剩余的有效期，过期后 Redis 自动清理，黑名单不会无限增长。
 *
 * 为什么 refresh 用白名单、access 用黑名单：refresh 寿命长且数量少（一次登录一条），
 * 用白名单可以随时"全部踢下线"；access 寿命只有 30 分钟，用黑名单才不会给每个请求都留一条记录。
 */
@Component
public class TokenStore {

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redis;

    public TokenStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void saveRefreshToken(String jti, Long userId, Duration ttl) {
        redis.opsForValue().set(REFRESH_PREFIX + jti, String.valueOf(userId), ttl);
    }

    /** 返回白名单里记录的 userId；不存在返回 null。 */
    public Long findRefreshTokenOwner(String jti) {
        String value = redis.opsForValue().get(REFRESH_PREFIX + jti);
        return value == null ? null : Long.valueOf(value);
    }

    public void revokeRefreshToken(String jti) {
        redis.delete(REFRESH_PREFIX + jti);
    }

    public void blacklistAccessToken(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            // 已经过期的 token 不需要拉黑，它自己就失效了
            return;
        }
        redis.opsForValue().set(BLACKLIST_PREFIX + jti, "1", ttl);
    }

    public boolean isAccessTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti));
    }
}
