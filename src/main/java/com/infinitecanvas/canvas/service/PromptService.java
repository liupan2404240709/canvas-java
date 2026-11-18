package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.vo.PromptListVO;
import com.infinitecanvas.canvas.vo.PromptRefreshResultVO;
import com.infinitecanvas.canvas.vo.PromptRefreshSummaryVO;

import java.util.List;

/**
 * 提示词查询 + 源同步。
 *
 * <p>两个职责放一个接口里，是因为它们共用同一套"归一化 → 落库"的上下文；
 * 拆成两个接口会让 {@code PromptSyncServiceImpl} 和 {@code PromptServiceImpl}
 * 互相注入，反而绕。
 */
public interface PromptService {

    /**
     * 列表：关键字 + 标签 + 分栏筛选，按"源 → 源内顺序"稳定排序。
     *
     * @param tags 多选标签，**或**关系（命中任意一个即返回），和前端行为一致
     */
    PromptListVO list(Long userId, String keyword, List<String> tags, String category, long page, long pageSize);

    /**
     * 同步单个源。
     *
     * @param force true 时忽略 ETag 与内容摘要，强制重写（用于"我就要它重跑一遍"）
     */
    PromptRefreshResultVO refresh(Long userId, String sourceKey, boolean force);

    PromptRefreshSummaryVO refreshAll(Long userId, boolean force);

    /** 定时任务入口：扫描所有用户里到期的源，逐个同步。 */
    void refreshDue();
}
