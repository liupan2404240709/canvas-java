package com.infinitecanvas.canvas.security;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败计数。用 Redis 而不是内存 Map：
 * 1. 重启应用不会把攻击者的失败次数清零；
 * 2. 以后多实例部署时，计数天然共享。
 *
 * key 里带账号而不是 IP：目标是防"针对某个账号的密码爆破"；
 * 如果要做"同一 IP 撞库"的防护，再加一个按 IP 计数的 key，两者是不同维度。
 */
@Component
public class LoginAttemptGuard {

    private static final String PREFIX = "auth:login:fail:";

    private final StringRedisTemplate redis;
    private final LoginProtectionProperties properties;

    public LoginAttemptGuard(StringRedisTemplate redis, LoginProtectionProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** 剩余锁定秒数，0 表示未锁定。 */
    public long lockedSeconds(String account) {
        String value = redis.opsForValue().get(key(account));
        if (value == null) {
            return 0L;
        }
        int failures = Integer.parseInt(value);
        if (failures < properties.getMaxAttempts()) {
            return 0L;
        }
        Long ttl = redis.getExpire(key(account));
        return ttl == null || ttl < 0 ? 0L : ttl;
    }

    /** 记录一次失败，返回剩余可尝试次数（已锁定则为 0）。 */
    public int recordFailure(String account) {
        String key = key(account);
        Long failures = redis.opsForValue().increment(key);
        if (failures != null && failures == 1L) {
            // 只有第一次失败时设置过期时间，避免每次失败都把窗口往后推、导致锁定永远不解除
            redis.expire(key, properties.getLockDuration());
        }
        int used = failures == null ? 1 : failures.intValue();
        return Math.max(0, properties.getMaxAttempts() - used);
    }

    public void clear(String account) {
        redis.delete(key(account));
    }

    public Duration lockDuration() {
        return properties.getLockDuration();
    }

    private String key(String account) {
        // 统一小写：避免用 Admin / admin 绕开计数
        return PREFIX + account.toLowerCase();
    }
}
