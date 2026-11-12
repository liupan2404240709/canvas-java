package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.vo.HealthVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.Callable;

@Slf4j
@Service
@RequiredArgsConstructor
public class HealthService {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    @Value("${spring.application.name}")
    private String appName;

    @Value("${app.version:unknown}")
    private String version;

    public HealthVO check() {
        HealthVO.Component db = probe(() -> jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        HealthVO.Component redis = probe(() -> stringRedisTemplate.execute((RedisCallback<String>) RedisConnection::ping));
        boolean allUp = "UP".equals(db.status()) && "UP".equals(redis.status());
        return new HealthVO(appName, version, allUp ? "UP" : "DOWN", db, redis, LocalDateTime.now());
    }

    /** 探针统一收口：计时、吞异常、记录耗时，避免每个组件写一遍 try/catch。 */
    private HealthVO.Component probe(Callable<?> action) {
        long start = System.nanoTime();
        try {
            action.call();
            return HealthVO.Component.up(elapsedMs(start));
        } catch (Exception e) {
            log.warn("健康检查失败: {}", e.getMessage());
            return HealthVO.Component.down(elapsedMs(start), e.getMessage());
        }
    }

    private long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}
