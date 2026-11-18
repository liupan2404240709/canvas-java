package com.infinitecanvas.canvas.infra.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

/**
 * 标签云缓存。
 *
 * <p>为什么单独缓存标签而不是整页结果：
 * <ul>
 *   <li>列表本身走的是主键/索引分页，已经很快；</li>
 *   <li>标签云是 {@code JSON_TABLE} 把每行的 JSON 数组炸开再 DISTINCT，
 *       它是这个页面里唯一"跟数据量正相关且不可索引"的查询；</li>
 *   <li>标签云在同一批筛选条件下重复率极高（用户翻页时参数不变）。</li>
 * </ul>
 *
 * <p><b>失效策略用"版本号"而不是删 key</b>：
 * 同步成功后把 {@code prompt:tags:ver:{userId}} 加一，缓存 key 里带上版本号，
 * 旧 key 自然再也没人访问，靠 TTL 过期。相比 {@code KEYS prompt:tags:*:del}，
 * 好处是<b>一次写入只影响一个 key</b>，不需要 pattern 扫描（SCAN 在数据量大时也会打满 CPU）。
 *
 * <p>另外两点：
 * <ul>
 *   <li>空结果同样缓存（TTL 稍短）：否则一个关键字反复查不到时，每次都要打一遍 JSON_TABLE，
 *       这就是典型的"缓存穿透"；</li>
 *   <li>Redis 出问题不影响主流程：读写缓存都 try/catch，降级成直接查库。</li>
 * </ul>
 */
@Component
public class PromptTagCache {

    private static final Logger log = LoggerFactory.getLogger(PromptTagCache.class);

    private static final String VERSION_KEY = "prompt:tags:ver:";
    private static final String CACHE_KEY = "prompt:tags:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final PromptProperties properties;

    public PromptTagCache(RedisTemplate<String, Object> redisTemplate,
                          StringRedisTemplate stringRedisTemplate,
                          PromptProperties properties) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
        this.properties = properties;
    }

    public List<String> getOrLoad(Long userId, String keyword, List<Long> sourceIds, Supplier<List<String>> loader) {
        String key = key(userId, keyword, sourceIds);
        try {
            Object cached = redisTemplate.opsForValue().get(key);
            if (cached instanceof List<?> values) {
                return values.stream().map(String::valueOf).toList();
            }
        } catch (RuntimeException e) {
            log.warn("读取标签缓存失败，降级查库: {}", e.getMessage());
        }

        List<String> tags = loader.get();
        try {
            // 空列表也缓存：key 里带的是"这次查询的参数"，缓存空结果不会污染别的查询
            redisTemplate.opsForValue().set(key, tags, properties.getTagCacheTtl());
        } catch (RuntimeException e) {
            log.warn("写入标签缓存失败，忽略: {}", e.getMessage());
        }
        return tags;
    }

    /** 同步/删除源后调用，让该用户的所有标签缓存立即作废。 */
    public void invalidate(Long userId) {
        try {
            stringRedisTemplate.opsForValue().increment(VERSION_KEY + userId);
        } catch (RuntimeException e) {
            log.warn("标签缓存版本号自增失败，等 TTL 自然过期: {}", e.getMessage());
        }
    }

    private String key(Long userId, String keyword, List<Long> sourceIds) {
        // key 保持可读（redis-cli 里能直接看出是哪个用户、哪个关键字），
        // 不做哈希：这里没有超长参数，而且排查问题时可读性比省几字节更重要
        String version = currentVersion(userId);
        String sources = sourceIds == null || sourceIds.isEmpty() ? "none" : sourceIds.toString();
        return CACHE_KEY + userId + ":" + version + ":" + sources + ":" + (keyword == null ? "" : keyword);
    }

    private String currentVersion(Long userId) {
        try {
            String version = stringRedisTemplate.opsForValue().get(VERSION_KEY + userId);
            return version == null ? "0" : version;
        } catch (RuntimeException e) {
            // 拿不到版本号时用一个固定值，最坏情况是读到稍旧一版的缓存
            return "0";
        }
    }
}
