package com.infinitecanvas.canvas.infra.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 基于 Redis 的简易分布式锁。
 *
 * <p>为什么需要它：同步一个源是"查一次上游 + 写几千行"的重操作。
 * 定时任务和用户手动点「同步」很可能同时发生，不加锁会出现：
 * 两份请求都去拉上游，然后并发 upsert 同一批行，轻则重复劳动，
 * 重则互相把对方的差量删除结果覆盖掉。
 *
 * <p>三个细节，漏一个锁就不可靠：
 * <ol>
 *   <li><b>SET NX PX</b> 一条命令完成"不存在才设置 + 过期时间"。
 *       分成 {@code setIfAbsent} 再 {@code expire}，两步之间进程崩了就是死锁。</li>
 *   <li><b>value 存随机 token</b>：解锁时要确认"这把锁是我的"。
 *       如果直接 DEL，可能出现 A 超时释放了 B 的锁，C 又拿到锁的连锁错误。</li>
 *   <li><b>用 Lua 做"比对 + 删除"的原子操作</b>：GET 和 DEL 分两步，
 *       中间锁过期并被别人拿走时，就会误删别人的锁。</li>
 * </ol>
 *
 * <p>已知局限：这是"单实例 Redis"的锁，Redis 主从切换时可能丢锁（Redlock 也没解决这件事）。
 * 本项目里同步是幂等的（内容没变就跳过、upsert 可重复执行），丢锁最多多跑一次，可以接受。
 */
@Component
public class RedisLock {

    /** 解锁脚本：只有 value 等于自己的 token 才删。返回 1 表示删掉了，0 表示锁已不是自己的。 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;

    public RedisLock(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试加锁，不阻塞等待。
     *
     * @return 加锁成功返回 token（解锁时要原样传回），失败返回 null
     */
    public String tryLock(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    public void unlock(String key, String token) {
        if (token == null) {
            return;
        }
        redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
    }
}
