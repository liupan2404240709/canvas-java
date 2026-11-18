package com.infinitecanvas.canvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import com.infinitecanvas.canvas.entity.Prompt;
import com.infinitecanvas.canvas.entity.PromptSource;
import com.infinitecanvas.canvas.infra.lock.RedisLock;
import com.infinitecanvas.canvas.infra.prompt.ParsedPrompt;
import com.infinitecanvas.canvas.infra.prompt.PromptFetcher;
import com.infinitecanvas.canvas.infra.prompt.PromptJson;
import com.infinitecanvas.canvas.infra.prompt.PromptJsonParser;
import com.infinitecanvas.canvas.infra.prompt.PromptProperties;
import com.infinitecanvas.canvas.infra.prompt.PromptSyncException;
import com.infinitecanvas.canvas.infra.prompt.PromptTagCache;
import com.infinitecanvas.canvas.mapper.PromptMapper;
import com.infinitecanvas.canvas.mapper.PromptSourceMapper;
import com.infinitecanvas.canvas.service.PromptService;
import com.infinitecanvas.canvas.service.PromptSourceService;
import com.infinitecanvas.canvas.vo.PromptListVO;
import com.infinitecanvas.canvas.vo.PromptRefreshResultVO;
import com.infinitecanvas.canvas.vo.PromptRefreshSummaryVO;
import com.infinitecanvas.canvas.vo.PromptVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PromptServiceImpl implements PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptServiceImpl.class);

    /** 单条 upsert 语句最多带多少行。太大容易撞 max_allowed_packet，太小又多轮往返。 */
    private static final int UPSERT_BATCH = 200;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_ERROR_LENGTH = 500;

    /** 同步锁 TTL。必须大于"一次 fetch + 一次写库"的最长耗时，否则锁会在同步中途过期。 */
    private static final Duration SYNC_LOCK_TTL = Duration.ofMinutes(3);

    private static final String LOCK_PREFIX = "prompt:refresh:";

    private final PromptSourceService promptSourceService;
    private final PromptSourceMapper promptSourceMapper;
    private final PromptMapper promptMapper;
    private final PromptFetcher promptFetcher;
    private final PromptJsonParser promptJsonParser;
    private final PromptTagCache promptTagCache;
    private final PromptProperties promptProperties;
    private final RedisLock redisLock;
    private final TransactionTemplate transactionTemplate;

    public PromptServiceImpl(PromptSourceService promptSourceService,
                             PromptSourceMapper promptSourceMapper,
                             PromptMapper promptMapper,
                             PromptFetcher promptFetcher,
                             PromptJsonParser promptJsonParser,
                             PromptTagCache promptTagCache,
                             PromptProperties promptProperties,
                             RedisLock redisLock,
                             TransactionTemplate transactionTemplate) {
        this.promptSourceService = promptSourceService;
        this.promptSourceMapper = promptSourceMapper;
        this.promptMapper = promptMapper;
        this.promptFetcher = promptFetcher;
        this.promptJsonParser = promptJsonParser;
        this.promptTagCache = promptTagCache;
        this.promptProperties = promptProperties;
        this.redisLock = redisLock;
        this.transactionTemplate = transactionTemplate;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PromptListVO list(Long userId, String keyword, List<String> tags, String category, long page, long pageSize) {
        long safePage = Math.max(1, page);
        long safeSize = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;

        // 一次取出"启用中的源"：既用来算 categories，也用来限定查询范围。
        // 停用的源数据还在，但既不参与查询也不出现在分栏里——这就是"停用"和"删除"的区别。
        List<PromptSource> sources = promptSourceService.enabled(userId);
        List<String> categories = sources.stream().map(PromptSource::getName).toList();

        List<PromptSource> scoped = sources;
        if (isActiveCategory(category)) {
            scoped = sources.stream().filter(source -> category.equals(source.getName())).toList();
        }
        if (scoped.isEmpty()) {
            return new PromptListVO(List.of(), List.of(), categories, 0, safePage, safeSize);
        }

        List<Long> sourceIds = scoped.stream().map(PromptSource::getId).toList();
        Map<Long, PromptSource> sourceById = sources.stream()
                .collect(Collectors.toMap(PromptSource::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));

        LambdaQueryWrapper<Prompt> wrapper = new LambdaQueryWrapper<Prompt>()
                .eq(Prompt::getUserId, userId)
                .in(Prompt::getSourceId, sourceIds);

        if (kw != null) {
            wrapper.and(q -> q.like(Prompt::getTitle, kw)
                    .or().like(Prompt::getPrompt, kw)
                    .or().like(Prompt::getDescription, kw));
        }

        // 多选标签是"或"关系：命中任意一个就返回。
        // 前端是 tags.some(...)，后端必须一致，否则用户勾两个标签会得到空结果。
        List<String> effectiveTags = tags == null ? List.of() : tags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (!effectiveTags.isEmpty()) {
            wrapper.and(q -> {
                for (int i = 0; i < effectiveTags.size(); i++) {
                    if (i > 0) {
                        q.or();
                    }
                    // {0} 是预编译占位符，值不会被拼进 SQL，天然免疫注入
                    q.apply("JSON_CONTAINS(tags_json, JSON_QUOTE({0}))", effectiveTags.get(i));
                }
            });
        }

        // 排序：先按源（保持源的先后顺序），再按源内下标（保持上游策展顺序），最后用 id 兜底。
        // 没有这三个字段就没有稳定分页——MySQL 对"排序值相同的行"不保证顺序，翻页会重复或漏。
        wrapper.orderByAsc(Prompt::getSourceId)
                .orderByAsc(Prompt::getSortIndex)
                .orderByAsc(Prompt::getId);

        Page<Prompt> result = promptMapper.selectPage(new Page<>(safePage, safeSize), wrapper);

        List<String> tagFacet = promptTagCache.getOrLoad(userId, kw, sourceIds,
                () -> promptMapper.selectTagFacet(userId, sourceIds, kw, promptProperties.getTagFacetLimit()));

        List<PromptVO> items = result.getRecords().stream()
                .map(prompt -> toVO(prompt, sourceById.get(prompt.getSourceId())))
                .toList();

        return new PromptListVO(items, tagFacet, categories, result.getTotal(), safePage, safeSize);
    }

    private boolean isActiveCategory(String category) {
        return StringUtils.hasText(category) && !"all".equalsIgnoreCase(category.trim());
    }

    private PromptVO toVO(Prompt prompt, PromptSource source) {
        String sourceUrl = nullToEmpty(prompt.getSourceUrl());
        String homepage = source == null ? "" : nullToEmpty(source.getHomepage());
        return new PromptVO(
                prompt.getPromptKey(),
                prompt.getTitle(),
                prompt.getPrompt(),
                nullToEmpty(prompt.getDescription()),
                nullToEmpty(prompt.getCoverUrl()),
                PromptJson.read(prompt.getReferenceImageUrls()),
                PromptJson.read(prompt.getTagsJson()),
                nullToEmpty(prompt.getPreview()),
                PromptSourceServiceImpl.iso(prompt.getRemoteCreatedAt()),
                PromptSourceServiceImpl.iso(prompt.getRemoteUpdatedAt()),
                nullToEmpty(prompt.getAuthor()),
                sourceUrl,
                source == null ? "" : source.getSourceKey(),
                source == null ? "" : source.getName(),
                // 前端 Prompt.githubUrl = item.sourceUrl || source.homepage
                sourceUrl.isEmpty() ? homepage : sourceUrl,
                nullToEmpty(prompt.getImageMode()),
                nullToEmpty(prompt.getImageModel()),
                nullToEmpty(prompt.getImageSize()),
                prompt.getImageCount()
        );
    }

    // ------------------------------------------------------------------
    // 同步
    // ------------------------------------------------------------------

    @Override
    public PromptRefreshResultVO refresh(Long userId, String sourceKey, boolean force) {
        PromptSource source = promptSourceService.getOwned(userId, sourceKey);
        String lockKey = LOCK_PREFIX + source.getId();
        String token = redisLock.tryLock(lockKey, SYNC_LOCK_TTL);
        if (token == null) {
            // 不排队。同步是重操作，让用户干等 20 秒不如直接告诉他"正在同步"
            throw new BizException(ResultCode.PROMPT_SOURCE_BUSY);
        }
        try {
            return syncOne(source, force);
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    @Override
    public PromptRefreshSummaryVO refreshAll(Long userId, boolean force) {
        List<PromptSource> sources = promptSourceService.enabled(userId);
        List<PromptRefreshResultVO> results = new ArrayList<>();
        for (PromptSource source : sources) {
            results.add(refreshQuietly(source, force));
        }
        int total = results.stream().mapToInt(PromptRefreshResultVO::count).sum();
        int successCount = (int) results.stream().filter(PromptRefreshResultVO::success).count();
        int skippedCount = (int) results.stream().filter(PromptRefreshResultVO::skipped).count();
        return new PromptRefreshSummaryVO(results, total, successCount, results.size() - successCount, skippedCount);
    }

    @Override
    public void refreshDue() {
        List<PromptSource> due = promptSourceMapper.selectDueSources(promptProperties.getScheduleBatch());
        if (due.isEmpty()) {
            return;
        }
        for (PromptSource source : due) {
            PromptRefreshResultVO result = refreshQuietly(source, false);
            if (result.success()) {
                log.info("定时同步完成: source={} count={} skipped={}",
                        source.getSourceKey(), result.count(), result.skipped());
            } else {
                log.warn("定时同步失败: source={} error={}", source.getSourceKey(), result.lastError());
            }
        }
    }

    /**
     * 带锁执行一次同步，任何异常都转成"结果对象"，不向上抛。
     * 定时任务和"同步全部"都走这里，保证单个源失败不会中断整批。
     */
    private PromptRefreshResultVO refreshQuietly(PromptSource source, boolean force) {
        String lockKey = LOCK_PREFIX + source.getId();
        String token = redisLock.tryLock(lockKey, SYNC_LOCK_TTL);
        if (token == null) {
            return new PromptRefreshResultVO(
                    source.getSourceKey(), source.getName(), safeCount(source.getPromptCount()),
                    PromptSourceServiceImpl.iso(source.getLastSuccessAt()),
                    "该源正在同步中", false, true);
        }
        try {
            return syncOne(source, force);
        } catch (RuntimeException e) {
            // syncOne 内部已经把可预期的失败记进 last_error，走到这里说明是意外（比如数据库挂了）
            log.error("同步源出现未预期异常: source={}", source.getSourceKey(), e);
            return new PromptRefreshResultVO(
                    source.getSourceKey(), source.getName(), safeCount(source.getPromptCount()),
                    PromptSourceServiceImpl.iso(source.getLastSuccessAt()), "同步异常：" + e.getMessage(), false, false);
        } finally {
            redisLock.unlock(lockKey, token);
        }
    }

    private PromptRefreshResultVO syncOne(PromptSource source, boolean force) {
        LocalDateTime now = LocalDateTime.now();
        int previousCount = safeCount(source.getPromptCount());
        try {
            // force 时既不带条件请求头、也不比对摘要：用户明确要求"重跑一遍"
            PromptFetcher.FetchResult fetched = promptFetcher.fetch(
                    source.getUrl(),
                    force ? null : source.getEtag(),
                    force ? null : source.getLastModified());

            if (fetched.notModified()) {
                markSuccess(source.getId(), previousCount, source.getContentSha256(),
                        source.getEtag(), source.getLastModified(), now);
                return success(source, previousCount, now, true);
            }

            String contentHash = sha256(fetched.body());
            if (!force && contentHash.equals(source.getContentSha256())) {
                // 内容摘要一致：说明上游换了 ETag 但内容没变，或者上游根本不支持条件请求。
                // 不写提示词表，只刷新时间戳与校验值——这就是"第二次刷新不重复写库"。
                markSuccess(source.getId(), previousCount, contentHash,
                        fetched.etag(), fetched.lastModified(), now);
                return success(source, previousCount, now, true);
            }

            List<ParsedPrompt> parsed = promptJsonParser.parse(fetched.body(), source.getSourceKey(), source.getUrl());
            if (parsed.isEmpty() && source.getBuiltIn() == 1) {
                // 内置源突然返回空数组，大概率是上游临时故障。
                // 直接覆盖会把用户本地几千条提示词清空，宁可失败也不要这种"成功"。
                throw new PromptSyncException("内置源返回了 0 条提示词，已保留本地数据");
            }

            transactionTemplate.executeWithoutResult(status -> {
                upsertPrompts(source, parsed);
                // 差量删除放 upsert 之后：先保证新数据在，再删旧数据，
                // 万一中途失败（事务回滚），本地至少还是完整的旧快照
                promptMapper.deleteNotInKeys(source.getId(),
                        parsed.stream().map(ParsedPrompt::promptKey).toList());
                markSuccess(source.getId(), parsed.size(), contentHash, fetched.etag(), fetched.lastModified(), now);
            });

            // 数据变了，标签云缓存必须作废，否则用户刷新后还是旧的标签
            promptTagCache.invalidate(source.getUserId());
            return success(source, parsed.size(), now, false);
        } catch (PromptSyncException e) {
            return fail(source, now, e.getMessage());
        } catch (RuntimeException e) {
            log.error("同步源失败: source={}", source.getSourceKey(), e);
            return fail(source, now, "同步异常：" + e.getMessage());
        }
    }

    private void upsertPrompts(PromptSource source, List<ParsedPrompt> parsed) {
        List<Prompt> entities = new ArrayList<>(parsed.size());
        int sortIndex = 0;
        for (ParsedPrompt item : parsed) {
            Prompt prompt = new Prompt();
            prompt.setId(IdWorker.getId());
            prompt.setUserId(source.getUserId());
            prompt.setSourceId(source.getId());
            prompt.setPromptKey(item.promptKey());
            prompt.setTitle(item.title());
            prompt.setPrompt(item.prompt());
            prompt.setDescription(item.description());
            prompt.setCoverUrl(item.coverUrl());
            prompt.setReferenceImageUrls(PromptJson.write(item.referenceImageUrls()));
            prompt.setTagsJson(PromptJson.write(item.tags()));
            prompt.setPreview(item.preview());
            prompt.setAuthor(item.author());
            prompt.setSourceUrl(item.sourceUrl());
            prompt.setImageMode(item.imageMode());
            prompt.setImageModel(item.imageModel());
            prompt.setImageSize(item.imageSize());
            prompt.setImageCount(item.imageCount());
            prompt.setSortIndex(sortIndex++);
            prompt.setRemoteCreatedAt(item.remoteCreatedAt());
            prompt.setRemoteUpdatedAt(item.remoteUpdatedAt());
            entities.add(prompt);
        }
        for (int from = 0; from < entities.size(); from += UPSERT_BATCH) {
            int to = Math.min(from + UPSERT_BATCH, entities.size());
            promptMapper.upsertBatch(entities.subList(from, to));
        }
    }

    /** 成功：同时刷新条数、摘要、校验头和时间戳，并把 last_error 清成 NULL。 */
    private void markSuccess(Long sourceId, int count, String contentHash, String etag,
                             String lastModified, LocalDateTime now) {
        promptSourceMapper.update(null, new LambdaUpdateWrapper<PromptSource>()
                .eq(PromptSource::getId, sourceId)
                .set(PromptSource::getPromptCount, count)
                .set(PromptSource::getContentSha256, contentHash)
                .set(PromptSource::getEtag, etag)
                .set(PromptSource::getLastModified, lastModified)
                .set(PromptSource::getLastFetchedAt, now)
                .set(PromptSource::getLastSuccessAt, now)
                // 显式 set null：MyBatis-Plus 默认忽略 null 字段，不写这一行 last_error 永远清不掉
                .set(PromptSource::getLastError, null));
    }

    /** 失败：只记"什么时候试过 + 为什么失败"，成功时间和上次的内容摘要都不能动。 */
    private void markFailure(PromptSource source, LocalDateTime now, String error) {
        promptSourceMapper.update(null, new LambdaUpdateWrapper<PromptSource>()
                .eq(PromptSource::getId, source.getId())
                .set(PromptSource::getLastFetchedAt, now)
                .set(PromptSource::getLastError, cut(error, MAX_ERROR_LENGTH)));
    }

    private PromptRefreshResultVO success(PromptSource source, int count, LocalDateTime now, boolean skipped) {
        return new PromptRefreshResultVO(
                source.getSourceKey(), source.getName(), count,
                PromptSourceServiceImpl.iso(now), "", true, skipped);
    }

    private PromptRefreshResultVO fail(PromptSource source, LocalDateTime now, String error) {
        String message = cut(error, MAX_ERROR_LENGTH);
        markFailure(source, now, message);
        return new PromptRefreshResultVO(
                source.getSourceKey(), source.getName(), safeCount(source.getPromptCount()),
                PromptSourceServiceImpl.iso(source.getLastSuccessAt()), message, false, false);
    }

    private int safeCount(Integer count) {
        return count == null ? 0 : count;
    }

    private String cut(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // JDK 一定带 SHA-256，这里不可能发生
            throw new IllegalStateException(e);
        }
    }
}
