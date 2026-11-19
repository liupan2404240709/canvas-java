package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.dto.ChannelProbeRequest;
import com.infinitecanvas.canvas.dto.CreateChannelRequest;
import com.infinitecanvas.canvas.dto.UpdateChannelRequest;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.ChannelService;
import com.infinitecanvas.canvas.vo.ChannelPresetVO;
import com.infinitecanvas.canvas.vo.ChannelProbeVO;
import com.infinitecanvas.canvas.vo.ChannelVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 渠道管理。路径里用业务键 {@code channelKey}，不用数据库主键——
 * 它同时是前端模型值 {@code channelKey::modelName} 的前半截，对外必须稳定。
 *
 * <p>{@code /presets} 和 {@code /probe} 是字面路径，和 {@code /{channelKey}} 并存不会冲突：
 * Spring 的 {@code PathPatternParser} 里字面量优先级高于占位符，
 * 所以请求 {@code /channels/presets} 不会被当成 channelKey="presets"。
 */
@Tag(name = "渠道", description = "模型渠道的增删改查、连通性测试、拉取上游模型")
@RestController
@RequestMapping(ApiPath.CHANNELS)
public class ChannelController {

    private final ChannelService channelService;

    public ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @Operation(summary = "渠道列表", description = "含各自模型；只返回脱敏后的 Key（sk-a****wxyz）")
    @GetMapping
    public Result<List<ChannelVO>> list() {
        return Result.ok(channelService.list(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "厂商预设",
            description = "国产厂商的 Base URL / 协议 / 推荐模型，不含 Key；前端用它做「一键选厂商」")
    @GetMapping("/presets")
    public Result<List<ChannelPresetVO>> presets() {
        return Result.ok(channelService.presets());
    }

    @Operation(summary = "渠道详情")
    @GetMapping("/{channelKey}")
    public Result<ChannelVO> get(@PathVariable String channelKey) {
        return Result.ok(channelService.get(SecurityUtils.currentUserId(), channelKey));
    }

    @Operation(summary = "新建渠道", description = "API Key 必填，落库前 AES-GCM 加密；地址不合法返回 40050")
    @PostMapping
    public Result<ChannelVO> create(@Valid @RequestBody CreateChannelRequest request) {
        return Result.ok(channelService.create(SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "保存渠道配置",
            description = "null = 不改；apiKey 留空表示保持原值；models 为空数组表示清空模型")
    @PutMapping("/{channelKey}")
    public Result<ChannelVO> update(@PathVariable String channelKey,
                                    @Valid @RequestBody UpdateChannelRequest request) {
        return Result.ok(channelService.update(SecurityUtils.currentUserId(), channelKey, request));
    }

    @Operation(summary = "启停渠道", description = "快捷开关，避免为一个布尔值传整份配置")
    @PatchMapping("/{channelKey}/enabled")
    public Result<ChannelVO> setEnabled(
            @PathVariable String channelKey,
            @Parameter(description = "true = 启用") @RequestParam boolean value) {
        return Result.ok(channelService.setEnabled(SecurityUtils.currentUserId(), channelKey, value));
    }

    @Operation(summary = "删除渠道", description = "逻辑删除渠道，同时物理删除它下面的模型")
    @DeleteMapping("/{channelKey}")
    public Result<Void> delete(@PathVariable String channelKey) {
        channelService.delete(SecurityUtils.currentUserId(), channelKey);
        return Result.ok(null);
    }

    @Operation(summary = "探测配置（不保存）",
            description = "填完地址和 Key 先测一下再保存。apiKey 可留空（配合 channelKey 用已存的 Key）。"
                    + "探测失败不抛 HTTP 错误，看 ok 字段")
    @PostMapping("/probe")
    public Result<ChannelProbeVO> probe(@Valid @RequestBody ChannelProbeRequest request) {
        return Result.ok(channelService.probe(SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "测试已保存的渠道", description = "结果会写进 lastTestOk / lastTestError，供列表页显示")
    @PostMapping("/{channelKey}/test")
    public Result<ChannelProbeVO> test(@PathVariable String channelKey) {
        return Result.ok(channelService.test(SecurityUtils.currentUserId(), channelKey));
    }
}
