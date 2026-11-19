package com.infinitecanvas.canvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import com.infinitecanvas.canvas.dto.ChannelModelDTO;
import com.infinitecanvas.canvas.dto.ChannelProbeRequest;
import com.infinitecanvas.canvas.dto.CreateChannelRequest;
import com.infinitecanvas.canvas.dto.UpdateChannelRequest;
import com.infinitecanvas.canvas.entity.Channel;
import com.infinitecanvas.canvas.entity.ChannelModel;
import com.infinitecanvas.canvas.infra.channel.ChannelApiUrls;
import com.infinitecanvas.canvas.infra.channel.ChannelPresets;
import com.infinitecanvas.canvas.infra.channel.ChannelProbe;
import com.infinitecanvas.canvas.infra.channel.ModelCapability;
import com.infinitecanvas.canvas.infra.crypto.SecretCipher;
import com.infinitecanvas.canvas.mapper.ChannelMapper;
import com.infinitecanvas.canvas.mapper.ChannelModelMapper;
import com.infinitecanvas.canvas.service.ChannelService;
import com.infinitecanvas.canvas.vo.ChannelModelVO;
import com.infinitecanvas.canvas.vo.ChannelPresetVO;
import com.infinitecanvas.canvas.vo.ChannelProbeVO;
import com.infinitecanvas.canvas.vo.ChannelVO;
import com.infinitecanvas.canvas.vo.ModelCandidateVO;
import com.infinitecanvas.canvas.vo.ModelOptionVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ChannelServiceImpl implements ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelServiceImpl.class);

    private static final String FORMAT_OPENAI = "openai";
    private static final String FORMAT_GEMINI = "gemini";
    private static final int MAX_NAME = 100;
    private static final int MAX_ERROR = 500;

    private final ChannelMapper channelMapper;
    private final ChannelModelMapper channelModelMapper;
    private final SecretCipher secretCipher;
    private final ChannelProbe channelProbe;

    public ChannelServiceImpl(ChannelMapper channelMapper,
                              ChannelModelMapper channelModelMapper,
                              SecretCipher secretCipher,
                              ChannelProbe channelProbe) {
        this.channelMapper = channelMapper;
        this.channelModelMapper = channelModelMapper;
        this.secretCipher = secretCipher;
        this.channelProbe = channelProbe;
    }

    @Override
    public List<ChannelVO> list(Long userId) {
        List<Channel> channels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getUserId, userId)
                .orderByAsc(Channel::getSortIndex)
                .orderByAsc(Channel::getId));
        if (channels.isEmpty()) {
            return List.of();
        }
        // 一次把所有模型查出来再按 channel_id 分组。逐个渠道查是 N+1：
        // 10 个渠道 = 11 次往返，而这里永远是 2 次。
        List<Long> channelIds = channels.stream().map(Channel::getId).toList();
        Map<Long, List<ChannelModel>> grouped = channelModelMapper.selectList(
                        new LambdaQueryWrapper<ChannelModel>()
                                .in(ChannelModel::getChannelId, channelIds)
                                .orderByAsc(ChannelModel::getSortIndex)
                                .orderByAsc(ChannelModel::getId))
                .stream()
                .collect(Collectors.groupingBy(ChannelModel::getChannelId));

        return channels.stream()
                .map(channel -> toVO(channel, grouped.getOrDefault(channel.getId(), List.of())))
                .toList();
    }

    @Override
    public ChannelVO get(Long userId, String channelKey) {
        Channel channel = requireChannel(userId, channelKey);
        List<ChannelModel> models = channelModelMapper.selectList(new LambdaQueryWrapper<ChannelModel>()
                .eq(ChannelModel::getChannelId, channel.getId())
                .orderByAsc(ChannelModel::getSortIndex)
                .orderByAsc(ChannelModel::getId));
        return toVO(channel, models);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelVO create(Long userId, CreateChannelRequest request) {
        String baseUrl = requireBaseUrl(request.baseUrl());
        String apiKey = requireApiKey(request.apiKey());
        String apiFormat = normalizeFormat(request.apiFormat(), FORMAT_OPENAI);

        Channel channel = new Channel();
        channel.setUserId(userId);
        // 业务键由后端生成：前端只关心"地址 + Key"，key 的格式和唯一性不该由它负责
        channel.setChannelKey("ch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        channel.setName(resolveName(request.name(), baseUrl));
        channel.setBaseUrl(baseUrl);
        channel.setApiKeyCipher(secretCipher.encrypt(apiKey));
        channel.setApiKeyHint(secretCipher.mask(apiKey));
        channel.setApiFormat(apiFormat);
        channel.setEnabled(Boolean.FALSE.equals(request.enabled()) ? 0 : 1);
        channel.setSortIndex(0);
        channelMapper.insert(channel);

        List<ChannelModel> models = insertModels(userId, channel.getId(), request.models());
        log.info("渠道已创建 user={} key={} models={}", userId, channel.getChannelKey(), models.size());
        return toVO(channel, models);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelVO update(Long userId, String channelKey, UpdateChannelRequest request) {
        Channel channel = requireChannel(userId, channelKey);

        // 用"只装有变化的字段"的实体 + updateById，天然就是局部更新：
        // MyBatis-Plus 默认不给 null 字段生成 SET 子句，正好符合"null = 不改"。
        Channel patch = new Channel();
        patch.setId(channel.getId());
        boolean changed = false;
        boolean endpointChanged = false;

        if (request.name() != null) {
            patch.setName(resolveName(request.name(), channel.getBaseUrl()));
            changed = true;
        }
        if (request.baseUrl() != null) {
            String baseUrl = requireBaseUrl(request.baseUrl());
            patch.setBaseUrl(baseUrl);
            endpointChanged |= !baseUrl.equals(channel.getBaseUrl());
            changed = true;
        }
        if (StringUtils.hasText(request.apiKey())) {
            String apiKey = request.apiKey().trim();
            patch.setApiKeyCipher(secretCipher.encrypt(apiKey));
            patch.setApiKeyHint(secretCipher.mask(apiKey));
            // 换了 Key 之后"上次测试通过"就不再成立了
            endpointChanged = true;
            changed = true;
        }
        if (request.apiFormat() != null) {
            String apiFormat = normalizeFormat(request.apiFormat(), channel.getApiFormat());
            patch.setApiFormat(apiFormat);
            endpointChanged |= !apiFormat.equals(channel.getApiFormat());
            changed = true;
        }
        if (request.enabled() != null) {
            patch.setEnabled(request.enabled() ? 1 : 0);
            changed = true;
        }
        if (request.sortIndex() != null) {
            patch.setSortIndex(request.sortIndex());
            changed = true;
        }
        if (changed) {
            channelMapper.updateById(patch);
        }
        if (endpointChanged) {
            clearTestState(channel.getId());
        }
        // models 为 null 表示"这次不动模型"，和空数组（清空）是两回事
        if (request.models() != null) {
            replaceModels(userId, channel.getId(), request.models());
        }
        return get(userId, channelKey);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, String channelKey) {
        Channel channel = requireChannel(userId, channelKey);
        // 模型没有独立生命周期，渠道删了就物理删。
        // 逻辑删除会让 uk_channel_model_name 挡住"以后重建同名模型"，而且这些行永远不会被读到。
        channelModelMapper.delete(new LambdaQueryWrapper<ChannelModel>()
                .eq(ChannelModel::getChannelId, channel.getId()));
        channelMapper.deleteById(channel.getId());
        log.info("渠道已删除 user={} key={}", userId, channelKey);
    }

    @Override
    public ChannelVO setEnabled(Long userId, String channelKey, boolean enabled) {
        Channel channel = requireChannel(userId, channelKey);
        if ((channel.getEnabled() != null && channel.getEnabled() == 1) != enabled) {
            channelMapper.update(null, new LambdaUpdateWrapper<Channel>()
                    .eq(Channel::getId, channel.getId())
                    .set(Channel::getEnabled, enabled ? 1 : 0)
                    // wrapper 更新不会触发 MetaObjectHandler，updated_at 要自己写
                    .set(Channel::getUpdatedAt, LocalDateTime.now()));
        }
        return get(userId, channelKey);
    }

    @Override
    public ChannelProbeVO probe(Long userId, ChannelProbeRequest request) {
        String baseUrl = requireBaseUrl(request.baseUrl());
        String apiFormat = normalizeFormat(request.apiFormat(), FORMAT_OPENAI);

        Channel channel = StringUtils.hasText(request.channelKey())
                ? requireChannel(userId, request.channelKey())
                : null;

        String apiKey;
        if (StringUtils.hasText(request.apiKey())) {
            apiKey = request.apiKey().trim();
        } else if (channel != null) {
            // 已保存的渠道不需要前端把明文拿在手上（它也拿不到，接口只回 hint）
            apiKey = secretCipher.decrypt(channel.getApiKeyCipher());
        } else {
            throw new BizException(ResultCode.CHANNEL_API_KEY_REQUIRED);
        }

        ChannelProbe.Outcome outcome = channelProbe.probe(baseUrl, apiKey, apiFormat);
        return toProbeVO(outcome, channel == null ? null : channel.getId());
    }

    @Override
    public ChannelProbeVO test(Long userId, String channelKey) {
        Channel channel = requireChannel(userId, channelKey);
        String apiKey = secretCipher.decrypt(channel.getApiKeyCipher());
        ChannelProbe.Outcome outcome = channelProbe.probe(channel.getBaseUrl(), apiKey, channel.getApiFormat());

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<Channel> wrapper = new LambdaUpdateWrapper<Channel>()
                .eq(Channel::getId, channel.getId())
                .set(Channel::getLastTestAt, now)
                .set(Channel::getLastTestOk, outcome.ok() ? 1 : 0)
                // 成功时必须显式写 NULL。updateById 会忽略 null 字段，
                // 上一次的失败原因就会永久留在库里，前端一直显示"连接失败"
                .set(Channel::getLastTestError, outcome.ok() ? null : truncate(outcome.message()))
                .set(Channel::getUpdatedAt, now);
        channelMapper.update(null, wrapper);

        return toProbeVO(outcome, channel.getId());
    }

    @Override
    public List<ModelOptionVO> models(Long userId, String capability) {
        ModelCapability parsed = null;
        if (StringUtils.hasText(capability)) {
            parsed = ModelCapability.parse(capability);
            if (parsed == null) {
                throw new BizException(ResultCode.CHANNEL_CAPABILITY_INVALID);
            }
        }

        List<Channel> enabledChannels = channelMapper.selectList(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getUserId, userId)
                .eq(Channel::getEnabled, 1)
                .orderByAsc(Channel::getSortIndex)
                .orderByAsc(Channel::getId));
        if (enabledChannels.isEmpty()) {
            return List.of();
        }
        // 渠道顺序决定下拉框的分组顺序，先记下位置，最后统一排序
        Map<Long, Integer> channelOrder = new HashMap<>();
        Map<Long, Channel> channelById = new HashMap<>();
        for (int i = 0; i < enabledChannels.size(); i++) {
            Channel channel = enabledChannels.get(i);
            channelOrder.put(channel.getId(), i);
            channelById.put(channel.getId(), channel);
        }

        String capCode = parsed == null ? null : parsed.code();
        List<ChannelModel> models = channelModelMapper.selectList(new LambdaQueryWrapper<ChannelModel>()
                .eq(ChannelModel::getUserId, userId)
                .eq(ChannelModel::getEnabled, 1)
                .eq(capCode != null, ChannelModel::getCapability, capCode));

        // 停用渠道下的模型必须一起过滤掉：t_channel_model 只冗余了 user_id，
        // 不带渠道状态，直接返回等于"停用了渠道但模型还能选"
        return models.stream()
                .filter(model -> channelById.containsKey(model.getChannelId()))
                .sorted(Comparator
                        .comparingInt((ChannelModel model) ->
                                channelOrder.getOrDefault(model.getChannelId(), Integer.MAX_VALUE))
                        .thenComparingInt(model -> model.getSortIndex() == null ? 0 : model.getSortIndex()))
                .map(model -> {
                    Channel channel = channelById.get(model.getChannelId());
                    return new ModelOptionVO(
                            channel.getChannelKey() + "::" + model.getName(),
                            channel.getChannelKey(),
                            channel.getName(),
                            model.getName(),
                            model.getCapability());
                })
                .toList();
    }

    @Override
    public List<ChannelPresetVO> presets() {
        return ChannelPresets.ALL.stream()
                .map(preset -> new ChannelPresetVO(
                        preset.key(),
                        preset.name(),
                        preset.baseUrl(),
                        preset.apiFormat(),
                        preset.homepage(),
                        preset.note(),
                        preset.models().stream()
                                .map(model -> new ChannelPresetVO.Model(model.name(), model.capability()))
                                .toList()))
                .toList();
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private Channel requireChannel(Long userId, String channelKey) {
        if (!StringUtils.hasText(channelKey)) {
            throw new BizException(ResultCode.CHANNEL_NOT_FOUND);
        }
        // user_id 放进 WHERE 而不是查出来再比对：越权访问直接查不到，
        // 返回 404 而不是 403，不泄露"这个 key 存在但不属于你"
        Channel channel = channelMapper.selectOne(new LambdaQueryWrapper<Channel>()
                .eq(Channel::getUserId, userId)
                .eq(Channel::getChannelKey, channelKey));
        if (channel == null) {
            throw new BizException(ResultCode.CHANNEL_NOT_FOUND);
        }
        return channel;
    }

    private List<ChannelModel> insertModels(Long userId, Long channelId, List<ChannelModelDTO> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return List.of();
        }
        List<ChannelModel> created = new ArrayList<>();
        int index = 0;
        for (ChannelModelDTO dto : dedupe(incoming)) {
            ChannelModel model = new ChannelModel();
            model.setUserId(userId);
            model.setChannelId(channelId);
            model.setName(dto.name().trim());
            model.setCapability(capabilityOf(dto.capability(), dto.name()));
            model.setScript(trimScript(dto.script()));
            model.setEnabled(Boolean.FALSE.equals(dto.enabled()) ? 0 : 1);
            model.setSortIndex(index++);
            channelModelMapper.insert(model);
            created.add(model);
        }
        return created;
    }

    /**
     * 把渠道的模型列表整体替换成 {@code incoming}，但按名字做差量而不是全删全插。
     *
     * <p>全删全插的代价：每条记录的 {@code id} 和 {@code created_at} 每次都变。
     * 阶段 6 的生成任务要记住"用户上次用的哪个模型"，引用 id 比引用名字稳；
     * 而且全删全插在一个事务里会持有更多行锁。
     */
    private void replaceModels(Long userId, Long channelId, List<ChannelModelDTO> incoming) {
        List<ChannelModel> existing = channelModelMapper.selectList(new LambdaQueryWrapper<ChannelModel>()
                .eq(ChannelModel::getChannelId, channelId));
        Map<String, ChannelModel> existingByName = new HashMap<>();
        existing.forEach(model -> existingByName.put(model.getName(), model));

        Set<String> keep = new HashSet<>();
        int index = 0;
        for (ChannelModelDTO dto : dedupe(incoming)) {
            String name = dto.name().trim();
            String capability = capabilityOf(dto.capability(), name);
            String script = trimScript(dto.script());
            Integer enabled = Boolean.FALSE.equals(dto.enabled()) ? 0 : 1;
            ChannelModel current = existingByName.get(name);

            if (current == null) {
                ChannelModel model = new ChannelModel();
                model.setUserId(userId);
                model.setChannelId(channelId);
                model.setName(name);
                model.setCapability(capability);
                model.setScript(script);
                model.setEnabled(enabled);
                model.setSortIndex(index++);
                channelModelMapper.insert(model);
            } else {
                // 用 wrapper 而不是 updateById：后者会跳过 null 字段，
                // 用户想把脚本删掉（传空）时会变成"删不掉"
                channelModelMapper.update(null, new LambdaUpdateWrapper<ChannelModel>()
                        .eq(ChannelModel::getId, current.getId())
                        .set(ChannelModel::getCapability, capability)
                        .set(ChannelModel::getScript, script)
                        .set(ChannelModel::getEnabled, enabled)
                        .set(ChannelModel::getSortIndex, index++)
                        .set(ChannelModel::getUpdatedAt, LocalDateTime.now()));
            }
            keep.add(name);
        }

        List<Long> removeIds = existing.stream()
                .filter(model -> !keep.contains(model.getName()))
                .map(ChannelModel::getId)
                .toList();
        if (!removeIds.isEmpty()) {
            // 物理删除：这些行没有独立生命周期，逻辑删除只会让唯一键挡住同名重建
            channelModelMapper.deleteBatchIds(removeIds);
        }
    }

    /** 去掉空名、同名去重（保留第一次出现的顺序）。模型名是渠道内的唯一键，重复必然撞索引。 */
    private List<ChannelModelDTO> dedupe(List<ChannelModelDTO> incoming) {
        Map<String, ChannelModelDTO> unique = new LinkedHashMap<>();
        for (ChannelModelDTO dto : incoming) {
            if (dto == null || !StringUtils.hasText(dto.name())) {
                continue;
            }
            unique.putIfAbsent(dto.name().trim(), dto);
        }
        return new ArrayList<>(unique.values());
    }

    private String capabilityOf(String raw, String modelName) {
        if (!StringUtils.hasText(raw)) {
            // 客户端没传就猜一个：拉完上游模型直接确认的用户不该被逼着逐个选分类
            return ModelCapability.guess(modelName).code();
        }
        ModelCapability capability = ModelCapability.parse(raw);
        if (capability == null) {
            throw new BizException(ResultCode.CHANNEL_CAPABILITY_INVALID);
        }
        return capability.code();
    }

    private void clearTestState(Long channelId) {
        channelMapper.update(null, new LambdaUpdateWrapper<Channel>()
                .eq(Channel::getId, channelId)
                .set(Channel::getLastTestOk, null)
                .set(Channel::getLastTestError, null));
    }

    private String requireBaseUrl(String raw) {
        String normalized = ChannelApiUrls.normalize(raw);
        if (normalized == null) {
            throw new BizException(ResultCode.CHANNEL_BASE_URL_INVALID);
        }
        return normalized;
    }

    private String requireApiKey(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new BizException(ResultCode.CHANNEL_API_KEY_REQUIRED);
        }
        return raw.trim();
    }

    private String normalizeFormat(String raw, String fallback) {
        if (!StringUtils.hasText(raw)) {
            return StringUtils.hasText(fallback) ? fallback.toLowerCase(Locale.ROOT) : FORMAT_OPENAI;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!value.equals(FORMAT_OPENAI) && !value.equals(FORMAT_GEMINI)) {
            throw new BizException(ResultCode.BAD_REQUEST, "接口协议只能是 openai 或 gemini");
        }
        return value;
    }

    /** 名字留空时取地址里的主机名，比"未命名渠道 #3"有用得多。 */
    private String resolveName(String raw, String baseUrl) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            try {
                name = URI.create(baseUrl).getHost();
            } catch (RuntimeException e) {
                name = null;
            }
        }
        if (!StringUtils.hasText(name)) {
            name = "未命名渠道";
        }
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    private String trimScript(String raw) {
        return StringUtils.hasText(raw) ? raw : null;
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message;
    }

    private ChannelProbeVO toProbeVO(ChannelProbe.Outcome outcome, Long channelId) {
        Set<String> existing = Set.of();
        if (channelId != null) {
            existing = channelModelMapper.selectList(new LambdaQueryWrapper<ChannelModel>()
                            .select(ChannelModel::getName)
                            .eq(ChannelModel::getChannelId, channelId))
                    .stream()
                    .map(ChannelModel::getName)
                    .collect(Collectors.toSet());
        }
        Set<String> finalExisting = existing;
        List<ModelCandidateVO> candidates = outcome.models().stream()
                .map(name -> new ModelCandidateVO(
                        name,
                        ModelCapability.guess(name).code(),
                        finalExisting.contains(name)))
                .toList();
        return new ChannelProbeVO(outcome.ok(), outcome.message(), candidates,
                outcome.latencyMs(), outcome.httpStatus());
    }

    private ChannelVO toVO(Channel channel, List<ChannelModel> models) {
        List<ChannelModelVO> modelVOs = models.stream()
                .map(model -> new ChannelModelVO(
                        model.getName(),
                        model.getCapability(),
                        model.getEnabled() != null && model.getEnabled() == 1,
                        model.getSortIndex() == null ? 0 : model.getSortIndex(),
                        model.getScript()))
                .toList();
        return new ChannelVO(
                channel.getChannelKey(),
                channel.getName(),
                channel.getBaseUrl(),
                channel.getApiKeyHint(),
                channel.getApiFormat(),
                channel.getEnabled() != null && channel.getEnabled() == 1,
                channel.getSortIndex() == null ? 0 : channel.getSortIndex(),
                iso(channel.getLastTestAt()),
                // Integer 0/1 → Boolean，null 原样保留（= 从未测试过）
                channel.getLastTestOk() == null ? null : channel.getLastTestOk() == 1,
                channel.getLastTestError() == null ? "" : channel.getLastTestError(),
                modelVOs);
    }

    static String iso(LocalDateTime time) {
        return time == null ? "" : time.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
