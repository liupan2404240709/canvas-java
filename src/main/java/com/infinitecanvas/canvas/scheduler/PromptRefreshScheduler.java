package com.infinitecanvas.canvas.scheduler;

import com.infinitecanvas.canvas.service.PromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 提示词源的定时同步。
 *
 * <p>设计成"扫描 + 到期判断"两段，而不是给每个源注册一个定时器：
 * <ul>
 *   <li>源的刷新间隔是数据（存在 {@code refresh_interval_minutes}），不是启动时写死的；
 *       每个源一个 {@code ScheduledFuture} 意味着改间隔要重建任务，还要管理任务句柄；</li>
 *   <li>扫描每 5 分钟跑一次，单次只取 20 个到期源，天然限流；</li>
 *   <li>到期判断在 SQL 里做（{@code last_fetched_at <= now - interval}），
 *       用的是 {@code idx_prompt_source_due}，源再多也只是索引范围扫描。</li>
 * </ul>
 *
 * <p>多实例部署时，每个实例都会扫到同一批源，但真正的同步入口有 Redis 锁，
 * 拿不到锁的实例直接跳过——所以不会重复拉取。
 *
 * <p>为什么不直接在方法上加 {@code @Transactional}：同步是"网络请求 + 多批写库"，
 * 一个事务里等上游响应会把数据库连接占住几十秒，是最典型的"长事务"。
 * 事务边界只包住写批，网络请求在事务外。
 */
@Component
public class PromptRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(PromptRefreshScheduler.class);

    private final PromptService promptService;

    public PromptRefreshScheduler(PromptService promptService) {
        this.promptService = promptService;
    }

    /**
     * cron 从配置读，默认每 5 分钟扫一次。
     * 注意这是"扫描间隔"，不是"刷新间隔"：真正刷不刷由每个源自己的
     * {@code refresh_interval_minutes} 决定。
     */
    @Scheduled(cron = "${app.prompt.scan-cron:0 */5 * * * *}")
    public void scanDueSources() {
        try {
            promptService.refreshDue();
        } catch (RuntimeException e) {
            // 定时任务里必须自己兜住异常：虽然 Spring 的调度器会捕获并打日志，
            // 但这里补一条带上下文的日志，排查时能直接看出是调度整体失败还是单个源失败
            log.error("提示词定时同步整体失败", e);
        }
    }
}
