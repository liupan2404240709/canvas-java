package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.PageResult;
import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.dto.CopyProjectRequest;
import com.infinitecanvas.canvas.dto.CreateProjectRequest;
import com.infinitecanvas.canvas.dto.ImportProjectRequest;
import com.infinitecanvas.canvas.dto.SaveSnapshotRequest;
import com.infinitecanvas.canvas.dto.UpdateProjectRequest;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.CanvasProjectService;
import com.infinitecanvas.canvas.vo.ProjectDetailVO;
import com.infinitecanvas.canvas.vo.ProjectExportVO;
import com.infinitecanvas.canvas.vo.ProjectSummaryVO;
import com.infinitecanvas.canvas.vo.SaveSnapshotVO;
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
 * 画布项目接口。
 *
 * 路径里出现 projectId 是不可避免的（一个用户有多张画布），
 * 所以每个方法都必须把 userId 和 projectId 一起传给 Service 做归属校验——
 * 这是和 /users/me 的关键区别：能传 id 的地方就有越权风险。
 */
@Tag(name = "画布项目", description = "画布增删改查、快照保存、导入导出、回收站")
@RestController
@RequestMapping(ApiPath.PROJECTS)
public class CanvasProjectController {

    private final CanvasProjectService projectService;

    public CanvasProjectController(CanvasProjectService projectService) {
        this.projectService = projectService;
    }

    @Operation(summary = "创建画布", description = "只需标题，其余字段用默认值")
    @PostMapping
    public Result<ProjectSummaryVO> create(@Valid @RequestBody CreateProjectRequest request) {
        return Result.ok(projectService.create(SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "画布列表", description = "按更新时间倒序，支持标题关键字搜索")
    @GetMapping
    public Result<PageResult<ProjectSummaryVO>> list(
            @Parameter(description = "标题关键字，模糊匹配")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "页码，从 1 开始")
            @RequestParam(defaultValue = "1") long page,

            @Parameter(description = "每页条数，最大 100")
            @RequestParam(defaultValue = "20") long size) {
        return Result.ok(projectService.list(SecurityUtils.currentUserId(), keyword, page, size));
    }

    @Operation(summary = "画布详情", description = "含全部节点与连线，打开画布页时调一次")
    @GetMapping("/{id}")
    public Result<ProjectDetailVO> detail(@PathVariable Long id) {
        return Result.ok(projectService.detail(SecurityUtils.currentUserId(), id));
    }

    @Operation(summary = "更新画布", description = "重命名、切换背景、保存视口；只传需要改的字段")
    @PatchMapping("/{id}")
    public Result<ProjectSummaryVO> update(@PathVariable Long id, @Valid @RequestBody UpdateProjectRequest request) {
        return Result.ok(projectService.update(SecurityUtils.currentUserId(), id, request));
    }

    @Operation(summary = "删除画布", description = "逻辑删除，进入回收站，可用恢复接口还原")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        projectService.delete(SecurityUtils.currentUserId(), id);
        return Result.ok();
    }

    @Operation(summary = "复制画布", description = "连同节点和连线一起复制成一张新画布")
    @PostMapping("/{id}/copy")
    public Result<ProjectSummaryVO> copy(@PathVariable Long id,
                                         @Valid @RequestBody(required = false) CopyProjectRequest request) {
        return Result.ok(projectService.copy(SecurityUtils.currentUserId(), id,
                request == null ? new CopyProjectRequest(null) : request));
    }

    @Operation(summary = "保存快照", description = "整份替换节点与连线，带 version 乐观锁")
    @PutMapping("/{id}/snapshot")
    public Result<SaveSnapshotVO> saveSnapshot(@PathVariable Long id,
                                               @Valid @RequestBody SaveSnapshotRequest request) {
        return Result.ok(projectService.saveSnapshot(SecurityUtils.currentUserId(), id, request));
    }

    @Operation(summary = "导出画布", description = "返回可直接下载成 .json 的结构")
    @GetMapping("/{id}/export")
    public Result<ProjectExportVO> export(@PathVariable Long id) {
        return Result.ok(projectService.export(SecurityUtils.currentUserId(), id));
    }

    @Operation(summary = "导入画布", description = "请求体就是导出文件的内容，会创建成一张新画布")
    @PostMapping("/import")
    public Result<ProjectDetailVO> importProject(@Valid @RequestBody ImportProjectRequest request) {
        return Result.ok(projectService.importProject(SecurityUtils.currentUserId(), request));
    }

    @Operation(summary = "回收站列表", description = "只返回逻辑删除的画布摘要")
    @GetMapping("/trash")
    public Result<List<ProjectSummaryVO>> trash() {
        return Result.ok(projectService.trash(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "恢复画布", description = "从回收站还原，节点和连线都还在")
    @PostMapping("/{id}/restore")
    public Result<Void> restore(@PathVariable Long id) {
        projectService.restore(SecurityUtils.currentUserId(), id);
        return Result.ok();
    }
}
