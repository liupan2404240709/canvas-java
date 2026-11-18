package com.infinitecanvas.canvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import com.infinitecanvas.canvas.dto.CreatePromptSourceRequest;
import com.infinitecanvas.canvas.dto.UpdatePromptSourceRequest;
import com.infinitecanvas.canvas.entity.Prompt;
import com.infinitecanvas.canvas.entity.PromptSource;
import com.infinitecanvas.canvas.infra.prompt.BuiltInPromptSources;
import com.infinitecanvas.canvas.infra.prompt.PromptTagCache;
import com.infinitecanvas.canvas.infra.prompt.PromptUrls;
import com.infinitecanvas.canvas.mapper.PromptMapper;
import com.infinitecanvas.canvas.mapper.PromptSourceMapper;
import com.infinitecanvas.canvas.service.PromptSourceService;
import com.infinitecanvas.canvas.vo.PromptSourceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class PromptSourceServiceImpl implements PromptSourceService {

    private static final Logger log = LoggerFactory.getLogger(PromptSourceServiceImpl.class);

    private static final int DEFAULT_INTERVAL_MINUTES = 30;
    private static final int MAX_NAME = 100;

    private final PromptSourceMapper promptSourceMapper;
    private final PromptMapper promptMapper;
    private final PromptTagCache promptTagCache;

    public PromptSourceServiceImpl(PromptSourceMapper promptSourceMapper,
                                   PromptMapper promptMapper,
                                   PromptTagCache promptTagCache) {
        this.promptSourceMapper = promptSourceMapper;
        this.promptMapper = promptMapper;
        this.promptTagCache = promptTagCache;
    }

    @Override
    public List<PromptSourceVO> list(Long userId) {
        ensureBuiltIns(userId);
        List<PromptSource> sources = promptSourceMapper.selectList(new LambdaQueryWrapper<PromptSource>()
                .eq(PromptSource::getUserId, userId)
                .orderByDesc(PromptSource::getBuiltIn)
                .orderByAsc(PromptSource::getId));
        return sources.stream().map(this::toVO).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PromptSourceVO create(Long userId, CreatePromptSourceRequest request) {
        String url = requireHttpUrl(request.url());
        String name = trimName(request.name());
        requireNameAvailable(userId, name, null);

        PromptSource source = new PromptSource();
        source.setUserId(userId);
        // 自定义源的业务键由后端生成：前端只需要"名字 + 地址"，
        // 不像画布节点那样必须在本地先有 id 才能渲染
        source.setSourceKey("src_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        source.setName(name);
        source.setUrl(url);
        source.setHomepage(normalizeHomepage(request.homepage()));
        source.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        source.setBuiltIn(0);
        source.setRefreshIntervalMinutes(request.refreshIntervalMinutes() == null
                ? DEFAULT_INTERVAL_MINUTES : request.refreshIntervalMinutes());
        source.setPromptCount(0);
        promptSourceMapper.insert(source);
        return toVO(source);
    }

    @Override
    public PromptSourceVO update(Long userId, String sourceKey, UpdatePromptSourceRequest request) {
        PromptSource source = getOwned(userId, sourceKey);

        boolean structuralChange = request.name() != null || request.url() != null || request.homepage() != null;
        if (source.getBuiltIn() == 1 && structuralChange) {
            throw new BizException(ResultCode.PROMPT_SOURCE_READ_ONLY);
        }

        LambdaUpdateWrapper<PromptSource> wrapper = new LambdaUpdateWrapper<PromptSource>()
                .eq(PromptSource::getId, source.getId())
                .eq(PromptSource::getUserId, userId);

        boolean changed = false;
        if (request.name() != null) {
            String name = trimName(request.name());
            requireNameAvailable(userId, name, source.getId());
            wrapper.set(PromptSource::getName, name);
            changed = true;
        }
        if (request.url() != null) {
            wrapper.set(PromptSource::getUrl, requireHttpUrl(request.url()));
            changed = true;
        }
        if (request.homepage() != null) {
            wrapper.set(PromptSource::getHomepage, normalizeHomepage(request.homepage()));
            changed = true;
        }
        if (request.enabled() != null) {
            wrapper.set(PromptSource::getEnabled, request.enabled() ? 1 : 0);
            changed = true;
            // 停用后该源的提示词不再出现在列表里，标签云也跟着变
            promptTagCache.invalidate(userId);
        }
        if (request.refreshIntervalMinutes() != null) {
            wrapper.set(PromptSource::getRefreshIntervalMinutes, request.refreshIntervalMinutes());
            changed = true;
        }
        if (!changed) {
            return toVO(source);
        }
        promptSourceMapper.update(null, wrapper);
        return toVO(requireById(userId, source.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, String sourceKey) {
        PromptSource source = getOwned(userId, sourceKey);
        if (source.getBuiltIn() == 1) {
            throw new BizException(ResultCode.PROMPT_SOURCE_READ_ONLY);
        }
        // 提示词没有独立生命周期，源没了就一起物理删除。
        // 不用逻辑删除：谁能保证以后不会复用同一个 source_id？物理删干净最省心。
        promptMapper.delete(new LambdaQueryWrapper<Prompt>().eq(Prompt::getSourceId, source.getId()));
        promptSourceMapper.deleteById(source.getId());
        promptTagCache.invalidate(userId);
    }

    @Override
    public PromptSource getOwned(Long userId, String sourceKey) {
        PromptSource source = promptSourceMapper.selectOne(new LambdaQueryWrapper<PromptSource>()
                .eq(PromptSource::getUserId, userId)
                .eq(PromptSource::getSourceKey, sourceKey));
        if (source == null) {
            throw new BizException(ResultCode.PROMPT_SOURCE_NOT_FOUND);
        }
        return source;
    }

    @Override
    public List<PromptSource> enabled(Long userId) {
        ensureBuiltIns(userId);
        return promptSourceMapper.selectList(new LambdaQueryWrapper<PromptSource>()
                .eq(PromptSource::getUserId, userId)
                .eq(PromptSource::getEnabled, 1)
                .orderByAsc(PromptSource::getId));
    }

    /**
     * 播种内置源。幂等：先查已存在的 source_key，缺哪个补哪个。
     *
     * <p>为什么按用户各存一份，而不是做一张全局表：
     * 内置源的"启用/停用"和同步状态是<b>用户自己的</b>，共享一行就变成"谁先停用谁说了算"。
     * 代价是 7 行/用户的冗余，相对它带来的语义清晰度可以接受。
     */
    private void ensureBuiltIns(Long userId) {
        List<PromptSource> existing = promptSourceMapper.selectList(new LambdaQueryWrapper<PromptSource>()
                .select(PromptSource::getSourceKey)
                .eq(PromptSource::getUserId, userId));
        Set<String> keys = new HashSet<>();
        existing.forEach(source -> keys.add(source.getSourceKey()));

        for (BuiltInPromptSources.BuiltIn builtIn : BuiltInPromptSources.ALL) {
            if (keys.contains(builtIn.key())) {
                continue;
            }
            PromptSource source = new PromptSource();
            source.setUserId(userId);
            source.setSourceKey(builtIn.key());
            source.setName(builtIn.name());
            source.setUrl(builtIn.url());
            source.setHomepage(builtIn.homepage());
            source.setEnabled(1);
            source.setBuiltIn(1);
            source.setRefreshIntervalMinutes(DEFAULT_INTERVAL_MINUTES);
            source.setPromptCount(0);
            try {
                promptSourceMapper.insert(source);
            } catch (DuplicateKeyException e) {
                // 两个请求同时首次访问（比如页面同时发来 sources 和 prompts 两个请求），
                // 靠 uk_prompt_source_user_key 兜住，吞掉即可
                log.debug("内置源已被并发创建，跳过: {}", builtIn.key());
            }
        }
    }

    private PromptSource requireById(Long userId, Long id) {
        PromptSource source = promptSourceMapper.selectById(id);
        if (source == null || !source.getUserId().equals(userId)) {
            throw new BizException(ResultCode.PROMPT_SOURCE_NOT_FOUND);
        }
        return source;
    }

    private void requireNameAvailable(Long userId, String name, Long excludeId) {
        Long count = promptSourceMapper.selectCount(new LambdaQueryWrapper<PromptSource>()
                .eq(PromptSource::getUserId, userId)
                .eq(PromptSource::getName, name)
                .ne(excludeId != null, PromptSource::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ResultCode.PROMPT_SOURCE_NAME_TAKEN);
        }
    }

    private String trimName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            throw new BizException(ResultCode.BAD_REQUEST, "源名称不能为空");
        }
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    private String requireHttpUrl(String raw) {
        if (PromptUrls.parseHttp(raw) == null) {
            throw new BizException(ResultCode.PROMPT_SOURCE_URL_INVALID);
        }
        return raw.trim();
    }

    private String normalizeHomepage(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        if (PromptUrls.parseHttp(raw) == null) {
            throw new BizException(ResultCode.PROMPT_SOURCE_URL_INVALID, "主页必须是 http/https 地址");
        }
        return raw.trim();
    }

    /** 内置源只允许改 enabled / 刷新间隔，其余字段一律拒绝。 */
    private PromptSourceVO toVO(PromptSource source) {
        return new PromptSourceVO(
                source.getSourceKey(),
                source.getName(),
                source.getUrl(),
                source.getHomepage(),
                source.getEnabled() != null && source.getEnabled() == 1,
                source.getBuiltIn() != null && source.getBuiltIn() == 1,
                source.getRefreshIntervalMinutes() == null ? DEFAULT_INTERVAL_MINUTES : source.getRefreshIntervalMinutes(),
                source.getPromptCount() == null ? 0 : source.getPromptCount(),
                iso(source.getLastSuccessAt()),
                iso(source.getLastFetchedAt()),
                source.getLastError() == null ? "" : source.getLastError()
        );
    }

    /** ISO_LOCAL_DATE_TIME（带 T）：前端 `new Date(...)` 能稳定解析，不依赖各浏览器的宽松格式。 */
    static String iso(LocalDateTime time) {
        return time == null ? "" : time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
