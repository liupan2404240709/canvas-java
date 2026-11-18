package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.PageResult;
import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.dto.CreateAssetRequest;
import com.infinitecanvas.canvas.dto.UpdateAssetRequest;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.AssetService;
import com.infinitecanvas.canvas.vo.AssetVO;
import com.infinitecanvas.canvas.vo.TagCountVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "素材库", description = "文本/图片/视频/音频素材的增删改查、筛选与标签聚合")
@RestController
@RequestMapping(ApiPath.ASSETS)
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @Operation(summary = "新建素材", description = "文本素材传 content；媒体素材先调 /files/upload 拿 fileId 再传这里")
    @PostMapping
    public Result<AssetVO> create(@Valid @RequestBody CreateAssetRequest request) {
        return Result.ok(assetService.create(SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "素材列表", description = "按类型 / 关键字 / 标签筛选，创建时间倒序")
    @GetMapping
    public Result<PageResult<AssetVO>> list(
            @Parameter(description = "text / image / video / audio，不传或 all 表示不限")
            @RequestParam(required = false) String kind,

            @Parameter(description = "关键字，匹配标题/来源/备注/正文")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "标签，精确匹配")
            @RequestParam(required = false) String tag,

            @Parameter(description = "页码，从 1 开始")
            @RequestParam(defaultValue = "1") long page,

            @Parameter(description = "每页条数，最大 100")
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(assetService.list(SecurityUtils.currentUserId(), kind, keyword, tag, page, size));
    }

    @Operation(summary = "标签列表", description = "返回当前用户所有标签及使用次数，给筛选栏用")
    @GetMapping("/tags")
    public Result<List<TagCountVO>> tags() {
        return Result.ok(assetService.tags(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "素材详情")
    @GetMapping("/{id}")
    public Result<AssetVO> detail(@PathVariable Long id) {
        return Result.ok(assetService.detail(SecurityUtils.currentUserId(), id));
    }

    @Operation(summary = "更新素材", description = "只传需要改的字段；清空备注传空串，清空标签传 []")
    @PatchMapping("/{id}")
    public Result<AssetVO> update(@PathVariable Long id, @Valid @RequestBody UpdateAssetRequest request) {
        return Result.ok(assetService.update(SecurityUtils.currentUserId(), id, request));
    }

    @Operation(summary = "删除素材", description = "逻辑删除，磁盘文件不在这里删（见 /files/cleanup）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        assetService.delete(SecurityUtils.currentUserId(), id);
        return Result.ok();
    }
}
