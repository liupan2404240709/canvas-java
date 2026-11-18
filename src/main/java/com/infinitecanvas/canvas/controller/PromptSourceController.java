package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.dto.CreatePromptSourceRequest;
import com.infinitecanvas.canvas.dto.UpdatePromptSourceRequest;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.PromptService;
import com.infinitecanvas.canvas.service.PromptSourceService;
import com.infinitecanvas.canvas.vo.PromptRefreshResultVO;
import com.infinitecanvas.canvas.vo.PromptRefreshSummaryVO;
import com.infinitecanvas.canvas.vo.PromptSourceVO;
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

/**
 * 提示词源管理。
 *
 * <p>路径里用的是业务键 {@code sourceKey}（字符串）而不是数据库主键：
 * 前端的 {@code PromptSource.id} 本来就是字符串，内置源还是 registry 里的固定 id
 * （{@code banana-prompt-quicker}），用主键反而要额外做一次映射。
 */
@Tag(name = "提示词源", description = "源的新增/修改/停用/删除，以及手动同步")
@RestController
@RequestMapping(ApiPath.PROMPT_SOURCES)
public class PromptSourceController {

    private final PromptSourceService promptSourceService;
    private final PromptService promptService;

    public PromptSourceController(PromptSourceService promptSourceService, PromptService promptService) {
        this.promptSourceService = promptSourceService;
        this.promptService = promptService;
    }

    @Operation(summary = "源列表", description = "首次访问会自动播种 7 个内置源；返回值同时带同步状态")
    @GetMapping
    public Result<List<PromptSourceVO>> list() {
        return Result.ok(promptSourceService.list(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "新增源", description = "地址必须是 http/https 的 JSON 数组地址；同名会 409")
    @PostMapping
    public Result<PromptSourceVO> create(@Valid @RequestBody CreatePromptSourceRequest request) {
        return Result.ok(promptSourceService.create(SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "修改源", description = "局部更新；内置源只能改 enabled 和刷新间隔")
    @PatchMapping("/{sourceKey}")
    public Result<PromptSourceVO> update(@PathVariable String sourceKey,
                                         @Valid @RequestBody UpdatePromptSourceRequest request) {
        return Result.ok(promptSourceService.update(SecurityUtils.currentUserId(), sourceKey, request));
    }

    @Operation(summary = "删除源", description = "逻辑删除源，同时物理删除它下面的提示词")
    @DeleteMapping("/{sourceKey}")
    public Result<Void> delete(@PathVariable String sourceKey) {
        promptSourceService.delete(SecurityUtils.currentUserId(), sourceKey);
        return Result.ok(null);
    }

    @Operation(summary = "同步单个源",
            description = "成功返回同步后的条数；内容未变化时 skipped=true。同步失败不抛 HTTP 错误，看 success 字段")
    @PostMapping("/{sourceKey}/refresh")
    public Result<PromptRefreshResultVO> refresh(
            @PathVariable String sourceKey,

            @Parameter(description = "true 时忽略 ETag 与内容摘要，强制重写")
            @RequestParam(defaultValue = "false") boolean force) {
        return Result.ok(promptService.refresh(SecurityUtils.currentUserId(), sourceKey, force));
    }

    @Operation(summary = "同步全部源", description = "逐个同步启用中的源，单个失败不影响其他；串行执行，源多时耗时较长")
    @PostMapping("/refresh")
    public Result<PromptRefreshSummaryVO> refreshAll(
            @Parameter(description = "true 时全部强制重写")
            @RequestParam(defaultValue = "false") boolean force) {
        return Result.ok(promptService.refreshAll(SecurityUtils.currentUserId(), force));
    }
}
