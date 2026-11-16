package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.common.api.PageResult;
import com.infinitecanvas.canvas.dto.CopyProjectRequest;
import com.infinitecanvas.canvas.dto.CreateProjectRequest;
import com.infinitecanvas.canvas.dto.ImportProjectRequest;
import com.infinitecanvas.canvas.dto.SaveSnapshotRequest;
import com.infinitecanvas.canvas.dto.UpdateProjectRequest;
import com.infinitecanvas.canvas.vo.ProjectDetailVO;
import com.infinitecanvas.canvas.vo.ProjectExportVO;
import com.infinitecanvas.canvas.vo.ProjectSummaryVO;
import com.infinitecanvas.canvas.vo.SaveSnapshotVO;

import java.util.List;

/**
 * 画布项目业务。所有方法第一个参数都是 userId，
 * 归属校验放在 Service 而不是 Controller，避免以后被别的地方复用时漏掉。
 */
public interface CanvasProjectService {

    ProjectSummaryVO create(Long userId, CreateProjectRequest request);

    PageResult<ProjectSummaryVO> list(Long userId, String keyword, long page, long size);

    ProjectDetailVO detail(Long userId, Long projectId);

    ProjectSummaryVO update(Long userId, Long projectId, UpdateProjectRequest request);

    void delete(Long userId, Long projectId);

    ProjectSummaryVO copy(Long userId, Long projectId, CopyProjectRequest request);

    SaveSnapshotVO saveSnapshot(Long userId, Long projectId, SaveSnapshotRequest request);

    ProjectExportVO export(Long userId, Long projectId);

    ProjectDetailVO importProject(Long userId, ImportProjectRequest request);

    List<ProjectSummaryVO> trash(Long userId);

    void restore(Long userId, Long projectId);
}
