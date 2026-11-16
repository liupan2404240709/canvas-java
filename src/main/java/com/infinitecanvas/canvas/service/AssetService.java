package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.common.api.PageResult;
import com.infinitecanvas.canvas.dto.CreateAssetRequest;
import com.infinitecanvas.canvas.dto.UpdateAssetRequest;
import com.infinitecanvas.canvas.vo.AssetVO;
import com.infinitecanvas.canvas.vo.TagCountVO;

import java.util.List;

public interface AssetService {

    AssetVO create(Long userId, CreateAssetRequest request);

    /**
     * @param kind    text / image / video / audio，null 或 "all" 表示不限
     * @param keyword 标题 / 来源 / 备注 / 正文 模糊匹配
     * @param tag     精确匹配某个标签
     */
    PageResult<AssetVO> list(Long userId, String kind, String keyword, String tag, long page, long size);

    AssetVO detail(Long userId, Long assetId);

    AssetVO update(Long userId, Long assetId, UpdateAssetRequest request);

    void delete(Long userId, Long assetId);

    List<TagCountVO> tags(Long userId);
}
