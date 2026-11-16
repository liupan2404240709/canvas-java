package com.infinitecanvas.canvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infinitecanvas.canvas.common.api.PageResult;
import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import com.infinitecanvas.canvas.dto.CreateAssetRequest;
import com.infinitecanvas.canvas.dto.UpdateAssetRequest;
import com.infinitecanvas.canvas.entity.Asset;
import com.infinitecanvas.canvas.infra.storage.FileStorage;
import com.infinitecanvas.canvas.infra.storage.MediaTypes;
import com.infinitecanvas.canvas.mapper.AssetMapper;
import com.infinitecanvas.canvas.service.AssetService;
import com.infinitecanvas.canvas.service.FileService;
import com.infinitecanvas.canvas.vo.AssetDataVO;
import com.infinitecanvas.canvas.vo.AssetVO;
import com.infinitecanvas.canvas.vo.FileVO;
import com.infinitecanvas.canvas.vo.TagCountVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 素材库。素材是"元信息 + 一个可选文件"的组合：
 *
 * <ul>
 *   <li>文本素材（kind=text）：正文直接在 {@code content} 列里，没有文件；</li>
 *   <li>媒体素材（image/video/audio）：{@code file_id} 指向 t_file，
 *       宽高/字节数/MIME 都由后端从文件记录带出来。</li>
 * </ul>
 *
 * <p>为什么不把正文也塞进文件系统：文本只有几 KB，进数据库可以参与 LIKE 搜索，
 * 而图片进数据库则会让备份、迁移、CDN 全部变难。**小文本进库，大二进制出库**，
 * 这是内容管理类系统的通用分界线。
 */
@Service
public class AssetServiceImpl implements AssetService {

    private static final String KIND_TEXT = "text";
    private static final long DEFAULT_PAGE_SIZE = 20;
    private static final long MAX_PAGE_SIZE = 100;
    private static final int MAX_TITLE_LENGTH = 200;
    private static final int MAX_TAG_COUNT = 200;

    private final AssetMapper assetMapper;
    private final FileService fileService;
    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    public AssetServiceImpl(AssetMapper assetMapper,
                            FileService fileService,
                            FileStorage fileStorage,
                            ObjectMapper objectMapper) {
        this.assetMapper = assetMapper;
        this.fileService = fileService;
        this.fileStorage = fileStorage;
        this.objectMapper = objectMapper;
    }

    @Override
    public AssetVO create(Long userId, CreateAssetRequest request) {
        String kind = request.kind();
        Asset asset = new Asset();
        asset.setUserId(userId);
        asset.setKind(kind);
        asset.setTitle(trimTitle(request.title()));
        asset.setCoverUrl(trimToNull(request.coverUrl()));
        asset.setSource(trimToNull(request.source()));
        asset.setNote(trimToNull(request.note()));
        asset.setDurationMs(request.durationMs());
        asset.setTagsJson(writeTags(request.tags()));
        asset.setMetadataJson(writeJson(request.metadata()));

        if (KIND_TEXT.equals(kind)) {
            if (!StringUtils.hasText(request.content())) {
                throw new BizException(ResultCode.BAD_REQUEST, "文本素材必须提供 content");
            }
            if (request.fileId() != null) {
                throw new BizException(ResultCode.BAD_REQUEST, "文本素材不能关联文件");
            }
            asset.setContent(request.content());
        } else {
            if (request.fileId() == null) {
                throw new BizException(ResultCode.BAD_REQUEST, "媒体素材必须提供 fileId");
            }
            FileVO file = fileService.detail(userId, request.fileId());
            String fileCategory = MediaTypes.categoryOf(file.mimeType());
            if (!kind.equals(fileCategory)) {
                // 类型不匹配必须挡：把一段音频挂成 image 素材，前端渲染卡片时会拿到
                // 一个永远加载失败的 <img>，而这种错误在列表页很难定位
                throw new BizException(ResultCode.BAD_REQUEST,
                        "素材类型与文件类型不匹配：文件是 " + (fileCategory == null ? "未知" : fileCategory));
            }
            asset.setFileId(file.id());
            asset.setStorageKey(file.storageKey());
            asset.setBytes(file.bytes());
            asset.setMimeType(file.mimeType());
            asset.setWidth(file.width());
            asset.setHeight(file.height());
            if (!StringUtils.hasText(asset.getCoverUrl()) && MediaTypes.isImage(file.mimeType())) {
                // 前端有同样的默认逻辑（coverUrl 为空时用图片自身），放到后端做一次省得两个端各写一份
                asset.setCoverUrl(file.url());
            }
        }

        assetMapper.insert(asset);
        return toVO(asset);
    }

    @Override
    public PageResult<AssetVO> list(Long userId, String kind, String keyword, String tag, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;

        LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
                .eq(Asset::getUserId, userId)
                // 前端筛选栏传 "all"，后端也接受 null，两者都表示不限类型
                .eq(StringUtils.hasText(kind) && !"all".equals(kind), Asset::getKind, kind)
                .and(kw != null, w -> w.like(Asset::getTitle, kw)
                        .or().like(Asset::getSource, kw)
                        .or().like(Asset::getNote, kw)
                        .or().like(Asset::getContent, kw))
                // 标签精确匹配。JSON_QUOTE 负责把参数变成 JSON 字符串字面量（含引号），
                // 这样 {"人像"} 里的"人像"才和数组元素 '"人像"' 形式上一致；
                // {0} 是 MyBatis 的预编译占位符，不会被拼进 SQL，天然免疫注入
                .apply(StringUtils.hasText(tag), "JSON_CONTAINS(tags_json, JSON_QUOTE({0}))", tag)
                .orderByDesc(Asset::getCreatedAt)
                // 同一秒创建的素材再按 id 倒序，翻页才不会重复或漏
                .orderByDesc(Asset::getId);

        Page<Asset> result = assetMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<AssetVO> items = result.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(safePage, safeSize, result.getTotal(), items);
    }

    @Override
    public AssetVO detail(Long userId, Long assetId) {
        return toVO(requireAsset(userId, assetId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AssetVO update(Long userId, Long assetId, UpdateAssetRequest request) {
        Asset asset = requireAsset(userId, assetId);
        if (request.kind() != null && !Objects.equals(request.kind(), asset.getKind())) {
            throw new BizException(ResultCode.BAD_REQUEST, "素材类型不允许修改，请删除后重新创建");
        }

        LambdaUpdateWrapper<Asset> wrapper = new LambdaUpdateWrapper<Asset>()
                .eq(Asset::getId, assetId)
                .eq(Asset::getUserId, userId);
        boolean touched = false;

        if (request.title() != null) {
            wrapper.set(Asset::getTitle, trimTitle(request.title()));
            touched = true;
        }
        if (request.coverUrl() != null) {
            wrapper.set(Asset::getCoverUrl, trimToNull(request.coverUrl()));
            touched = true;
        }
        if (request.source() != null) {
            wrapper.set(Asset::getSource, trimToNull(request.source()));
            touched = true;
        }
        if (request.note() != null) {
            wrapper.set(Asset::getNote, trimToNull(request.note()));
            touched = true;
        }
        if (request.content() != null) {
            if (!KIND_TEXT.equals(asset.getKind())) {
                throw new BizException(ResultCode.BAD_REQUEST, "只有文本素材可以修改 content");
            }
            wrapper.set(Asset::getContent, request.content());
            touched = true;
        }
        if (request.tags() != null) {
            wrapper.set(Asset::getTagsJson, writeTags(request.tags()));
            touched = true;
        }
        if (request.metadata() != null) {
            wrapper.set(Asset::getMetadataJson, writeJson(request.metadata()));
            touched = true;
        }

        if (!touched) {
            return toVO(asset);
        }
        assetMapper.update(null, wrapper);
        // 走 update(null, wrapper) 时审计填充拿不到实体，updated_at 靠 MySQL 的
        // ON UPDATE CURRENT_TIMESTAMP 刷新，所以要重新查一次才能拿到新值
        return toVO(requireAsset(userId, assetId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long assetId) {
        requireAsset(userId, assetId);
        // 逻辑删除，和画布保持一致：素材数量多，误删概率高，先留着；
        // 对应的磁盘文件不在这里删——清理接口只处理"从未被任何素材引用过"的文件，
        // 所以删素材不会让字节消失，以后加素材回收站时还能恢复
        assetMapper.deleteById(assetId);
    }

    @Override
    public List<TagCountVO> tags(Long userId) {
        return assetMapper.selectTagCounts(userId, MAX_TAG_COUNT);
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    private Asset requireAsset(Long userId, Long assetId) {
        Asset asset = assetMapper.selectById(assetId);
        if (asset == null || !Objects.equals(asset.getUserId(), userId)) {
            throw new BizException(ResultCode.ASSET_NOT_FOUND);
        }
        return asset;
    }

    private AssetVO toVO(Asset asset) {
        return new AssetVO(
                asset.getId(),
                asset.getKind(),
                asset.getTitle(),
                asset.getCoverUrl(),
                readTags(asset.getTagsJson()),
                asset.getSource(),
                asset.getNote(),
                asset.getCreatedAt(),
                asset.getUpdatedAt(),
                readJson(asset.getMetadataJson()),
                buildData(asset)
        );
    }

    private AssetDataVO buildData(Asset asset) {
        if (KIND_TEXT.equals(asset.getKind())) {
            return new AssetDataVO(asset.getContent(), null, null, null, null, null, null, null);
        }
        return new AssetDataVO(
                null,
                // dataUrl 字段名对齐前端 ImageAsset.dataUrl；这里放的是服务端拼的公开地址
                StringUtils.hasText(asset.getStorageKey()) ? fileStorage.publicUrl(asset.getStorageKey()) : null,
                asset.getStorageKey(),
                asset.getWidth(),
                asset.getHeight(),
                asset.getBytes(),
                asset.getMimeType(),
                asset.getDurationMs()
        );
    }

    private String writeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        List<String> cleaned = tags.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        return cleaned.isEmpty() ? null : writeJson(objectMapper.valueToTree(cleaned));
    }

    private List<String> readTags(String tagsJson) {
        if (!StringUtils.hasText(tagsJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(tagsJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            // 库里的标签列坏了不该让整个列表 500，返回空标签继续渲染
            return List.of();
        }
    }

    private String writeJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new BizException(ResultCode.BAD_REQUEST, "扩展字段无法序列化");
        }
    }

    private JsonNode readJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("素材扩展字段不是合法 JSON：" + json, e);
        }
    }

    private String trimTitle(String title) {
        String value = title.trim();
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
