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
import com.infinitecanvas.canvas.dto.CanvasConnectionDTO;
import com.infinitecanvas.canvas.dto.CanvasNodeDTO;
import com.infinitecanvas.canvas.dto.CopyProjectRequest;
import com.infinitecanvas.canvas.dto.CreateProjectRequest;
import com.infinitecanvas.canvas.dto.ImportProjectRequest;
import com.infinitecanvas.canvas.dto.SaveSnapshotRequest;
import com.infinitecanvas.canvas.dto.UpdateProjectRequest;
import com.infinitecanvas.canvas.entity.CanvasEdge;
import com.infinitecanvas.canvas.entity.CanvasNode;
import com.infinitecanvas.canvas.entity.CanvasProject;
import com.infinitecanvas.canvas.mapper.CanvasEdgeMapper;
import com.infinitecanvas.canvas.mapper.CanvasNodeMapper;
import com.infinitecanvas.canvas.mapper.CanvasProjectMapper;
import com.infinitecanvas.canvas.service.CanvasProjectService;
import com.infinitecanvas.canvas.vo.CanvasConnectionVO;
import com.infinitecanvas.canvas.vo.CanvasNodeVO;
import com.infinitecanvas.canvas.vo.CanvasViewportVO;
import com.infinitecanvas.canvas.vo.ProjectDetailVO;
import com.infinitecanvas.canvas.vo.ProjectExportVO;
import com.infinitecanvas.canvas.vo.ProjectSummaryVO;
import com.infinitecanvas.canvas.vo.SaveSnapshotVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class CanvasProjectServiceImpl implements CanvasProjectService {

    private static final String DEFAULT_BACKGROUND_MODE = "lines";
    private static final String COPY_SUFFIX = " 副本";
    private static final String DEFAULT_IMPORT_TITLE = "导入的画布";
    private static final int MAX_TITLE_LENGTH = 100;
    private static final long DEFAULT_PAGE_SIZE = 20;
    private static final long MAX_PAGE_SIZE = 100;

    private final CanvasProjectMapper projectMapper;
    private final CanvasNodeMapper nodeMapper;
    private final CanvasEdgeMapper edgeMapper;
    private final ObjectMapper objectMapper;

    public CanvasProjectServiceImpl(CanvasProjectMapper projectMapper,
                                    CanvasNodeMapper nodeMapper,
                                    CanvasEdgeMapper edgeMapper,
                                    ObjectMapper objectMapper) {
        this.projectMapper = projectMapper;
        this.nodeMapper = nodeMapper;
        this.edgeMapper = edgeMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProjectSummaryVO create(Long userId, CreateProjectRequest request) {
        CanvasProject project = newProject(userId, trimTitle(request.title()));
        projectMapper.insert(project);
        return ProjectSummaryVO.from(project);
    }

    @Override
    public PageResult<ProjectSummaryVO> list(Long userId, String keyword, long page, long size) {
        long safePage = Math.max(page, 1);
        long safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        LambdaQueryWrapper<CanvasProject> wrapper = new LambdaQueryWrapper<CanvasProject>()
                .eq(CanvasProject::getUserId, userId)
                .like(StringUtils.hasText(keyword), CanvasProject::getTitle, keyword == null ? null : keyword.trim())
                // 更新时间倒序：最近动过的画布排前面，这是画布类产品的通用预期
                .orderByDesc(CanvasProject::getUpdatedAt)
                // 同一毫秒更新的两条（比如批量导入）再按 id 倒序，保证分页结果稳定不重复
                .orderByDesc(CanvasProject::getId);

        Page<CanvasProject> result = projectMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<ProjectSummaryVO> items = result.getRecords().stream().map(ProjectSummaryVO::from).toList();
        return new PageResult<>(safePage, safeSize, result.getTotal(), items);
    }

    @Override
    public ProjectDetailVO detail(Long userId, Long projectId) {
        CanvasProject project = requireProject(userId, projectId);
        return toDetail(project, loadNodes(projectId), loadEdges(projectId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectSummaryVO update(Long userId, Long projectId, UpdateProjectRequest request) {
        CanvasProject project = requireProject(userId, projectId);
        if (request.empty()) {
            return ProjectSummaryVO.from(project);
        }

        // 用 UpdateWrapper 逐字段 set：PATCH 语义下"没传"和"传了 null"要区别对待，
        // 走 updateById(entity) 会把没传的字段覆盖成 null。
        LambdaUpdateWrapper<CanvasProject> wrapper = new LambdaUpdateWrapper<CanvasProject>()
                .eq(CanvasProject::getId, projectId)
                .eq(CanvasProject::getUserId, userId);

        if (request.title() != null) {
            wrapper.set(CanvasProject::getTitle, trimTitle(request.title()));
        }
        if (request.backgroundMode() != null) {
            wrapper.set(CanvasProject::getBackgroundMode, request.backgroundMode().trim());
        }
        if (request.showImageInfo() != null) {
            wrapper.set(CanvasProject::getShowImageInfo, request.showImageInfo() ? 1 : 0);
        }
        if (request.viewport() != null) {
            wrapper.set(CanvasProject::getViewportX, request.viewport().x())
                    .set(CanvasProject::getViewportY, request.viewport().y())
                    .set(CanvasProject::getViewportK, request.viewport().k());
        }

        // 注意这里不动 version：改标题不影响画布内容，
        // 如果重命名也让别人的快照失效，协作时会出现大量无意义的冲突提示。
        projectMapper.update(null, wrapper);
        return ProjectSummaryVO.from(requireProject(userId, projectId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long projectId) {
        requireProject(userId, projectId);
        // 逻辑删除：只把 deleted 置 1，节点和连线保留，否则回收站恢复出来是个空画布
        projectMapper.deleteById(projectId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectSummaryVO copy(Long userId, Long projectId, CopyProjectRequest request) {
        CanvasProject source = requireProject(userId, projectId);
        List<CanvasNode> sourceNodes = loadNodes(projectId);
        List<CanvasEdge> sourceEdges = loadEdges(projectId);

        String title = StringUtils.hasText(request.title())
                ? trimTitle(request.title())
                : trimTitle(source.getTitle() + COPY_SUFFIX);

        CanvasProject copy = newProject(userId, title);
        copy.setBackgroundMode(source.getBackgroundMode());
        copy.setShowImageInfo(source.getShowImageInfo());
        copy.setViewportX(source.getViewportX());
        copy.setViewportY(source.getViewportY());
        copy.setViewportK(source.getViewportK());
        copy.setNodeCount(sourceNodes.size());
        copy.setConnectionCount(sourceEdges.size());
        projectMapper.insert(copy);

        // 节点的 node_key 在副本里原样复用：唯一索引是 (project_id, node_key)，
        // 跨项目重复没问题；这样连线里的 from/to 也不用重写。
        // 但数据库主键必须清空——直接复用原 id 会撞 PRIMARY KEY。
        insertNodes(copy.getId(), sourceNodes.stream().map(this::detachNode).toList());
        insertEdges(copy.getId(), sourceEdges.stream().map(this::detachEdge).toList());
        return ProjectSummaryVO.from(copy);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SaveSnapshotVO saveSnapshot(Long userId, Long projectId, SaveSnapshotRequest request) {
        CanvasProject project = requireProject(userId, projectId);

        // 第一道版本校验：客户端基于旧数据来保存，直接拒绝，不用等数据库报错
        if (!Objects.equals(project.getVersion(), request.version())) {
            throw new BizException(ResultCode.PROJECT_VERSION_CONFLICT);
        }

        List<CanvasNodeDTO> nodes = request.nodes() == null ? List.of() : request.nodes();
        List<CanvasConnectionDTO> connections = request.connections() == null ? List.of() : request.connections();
        validateGraph(nodes, connections);

        // 整份替换：先清空再写入。相比"逐条比对增删改"，
        // 逻辑只有一条路径，不会出现"删掉的节点残留在库里"这种 bug；
        // 代价是节点全部换了新主键（对前端无感，前端只认 node_key）。
        nodeMapper.delete(new LambdaQueryWrapper<CanvasNode>().eq(CanvasNode::getProjectId, projectId));
        edgeMapper.delete(new LambdaQueryWrapper<CanvasEdge>().eq(CanvasEdge::getProjectId, projectId));
        insertNodes(projectId, nodes.stream().map(node -> toNodeEntity(projectId, node)).toList());
        insertEdges(projectId, connections.stream().map(connection -> toEdgeEntity(projectId, connection)).toList());

        project.setNodeCount(nodes.size());
        project.setConnectionCount(connections.size());
        if (request.viewport() != null) {
            project.setViewportX(request.viewport().x());
            project.setViewportY(request.viewport().y());
            project.setViewportK(request.viewport().k());
        }

        // 第二道版本校验：并发的两个请求都通过了第一道，这里由 version 条件决出胜负。
        // 影响行数为 0 说明别人已经先提交了，抛异常让整个事务回滚（节点也不会被改脏）。
        int rows = projectMapper.updateById(project);
        if (rows == 0) {
            throw new BizException(ResultCode.PROJECT_VERSION_CONFLICT);
        }
        return new SaveSnapshotVO(project.getId(), project.getVersion(),
                project.getNodeCount(), project.getConnectionCount(), project.getUpdatedAt());
    }

    @Override
    public ProjectExportVO export(Long userId, Long projectId) {
        CanvasProject project = requireProject(userId, projectId);
        return new ProjectExportVO(
                ProjectExportVO.APP_NAME,
                ProjectExportVO.FORMAT_VERSION,
                LocalDateTime.now(),
                project.getTitle(),
                project.getBackgroundMode(),
                isTrue(project.getShowImageInfo()),
                toViewportVO(project),
                loadNodes(projectId).stream().map(this::toNodeVO).toList(),
                loadEdges(projectId).stream().map(this::toConnectionVO).toList()
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProjectDetailVO importProject(Long userId, ImportProjectRequest request) {
        if (request.formatVersion() != null && request.formatVersion() > ProjectExportVO.FORMAT_VERSION) {
            // 宁可直接拒绝，也不要"读一半"：字段含义变了以后静默丢数据更难排查
            throw new BizException(ResultCode.BAD_REQUEST,
                    "导入文件版本过高（" + request.formatVersion() + "），当前最多支持 " + ProjectExportVO.FORMAT_VERSION);
        }

        List<CanvasNodeDTO> nodes = request.nodes() == null ? List.of() : request.nodes();
        List<CanvasConnectionDTO> connections = request.connections() == null ? List.of() : request.connections();
        validateGraph(nodes, connections);

        String title = StringUtils.hasText(request.title()) ? trimTitle(request.title()) : DEFAULT_IMPORT_TITLE;
        CanvasProject project = newProject(userId, title);
        if (request.backgroundMode() != null) {
            project.setBackgroundMode(request.backgroundMode().trim());
        }
        if (request.showImageInfo() != null) {
            project.setShowImageInfo(request.showImageInfo() ? 1 : 0);
        }
        if (request.viewport() != null) {
            project.setViewportX(request.viewport().x());
            project.setViewportY(request.viewport().y());
            project.setViewportK(request.viewport().k());
        }
        project.setNodeCount(nodes.size());
        project.setConnectionCount(connections.size());
        projectMapper.insert(project);

        insertNodes(project.getId(), nodes.stream().map(node -> toNodeEntity(project.getId(), node)).toList());
        insertEdges(project.getId(), connections.stream().map(connection -> toEdgeEntity(project.getId(), connection)).toList());

        return toDetail(project, loadNodes(project.getId()), loadEdges(project.getId()));
    }

    @Override
    public List<ProjectSummaryVO> trash(Long userId) {
        return projectMapper.selectTrash(userId).stream().map(ProjectSummaryVO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long userId, Long projectId) {
        int rows = projectMapper.restore(projectId, userId);
        if (rows == 0) {
            // 已经恢复过、或 id 不属于当前用户，统一按"不存在"处理，不暴露别人的画布是否存在
            throw new BizException(ResultCode.PROJECT_NOT_FOUND);
        }
    }

    // ------------------------------------------------------------------
    // 内部方法
    // ------------------------------------------------------------------

    /** 查项目并校验归属。查不到和不是自己的都返回 404，避免用 403 泄露"这个 id 存在"。 */
    private CanvasProject requireProject(Long userId, Long projectId) {
        CanvasProject project = projectMapper.selectById(projectId);
        if (project == null || !Objects.equals(project.getUserId(), userId)) {
            throw new BizException(ResultCode.PROJECT_NOT_FOUND);
        }
        return project;
    }

    private CanvasProject newProject(Long userId, String title) {
        CanvasProject project = new CanvasProject();
        project.setUserId(userId);
        project.setTitle(title);
        project.setBackgroundMode(DEFAULT_BACKGROUND_MODE);
        project.setShowImageInfo(0);
        project.setViewportX(0d);
        project.setViewportY(0d);
        project.setViewportK(1d);
        project.setNodeCount(0);
        project.setConnectionCount(0);
        project.setVersion(0);
        return project;
    }

    private List<CanvasNode> loadNodes(Long projectId) {
        return nodeMapper.selectList(new LambdaQueryWrapper<CanvasNode>()
                .eq(CanvasNode::getProjectId, projectId)
                // 按主键升序 = 按插入顺序，保证每次打开画布渲染顺序一致（后插入的在上层）
                .orderByAsc(CanvasNode::getId));
    }

    private List<CanvasEdge> loadEdges(Long projectId) {
        return edgeMapper.selectList(new LambdaQueryWrapper<CanvasEdge>()
                .eq(CanvasEdge::getProjectId, projectId)
                .orderByAsc(CanvasEdge::getId));
    }

    private void insertNodes(Long projectId, List<CanvasNode> nodes) {
        if (nodes.isEmpty()) {
            return;
        }
        nodes.forEach(node -> node.setProjectId(projectId));
        nodeMapper.insertBatch(nodes);
    }

    private void insertEdges(Long projectId, List<CanvasEdge> edges) {
        if (edges.isEmpty()) {
            return;
        }
        edges.forEach(edge -> edge.setProjectId(projectId));
        edgeMapper.insertBatch(edges);
    }

    /**
     * 快照的图结构校验。数据库只能用唯一索引挡住重复 key，
     * "连线指向不存在的节点"这种事只能在这里挡——一旦落库，前端渲染时会报错，
     * 而且查起来要翻整份快照。
     */
    private void validateGraph(List<CanvasNodeDTO> nodes, List<CanvasConnectionDTO> connections) {
        Set<String> nodeKeys = new HashSet<>(Math.max(nodes.size() * 2, 16));
        for (CanvasNodeDTO node : nodes) {
            if (!nodeKeys.add(node.id())) {
                throw new BizException(ResultCode.BAD_REQUEST, "节点 id 重复：" + node.id());
            }
        }
        Set<String> edgeKeys = new HashSet<>(Math.max(connections.size() * 2, 16));
        for (CanvasConnectionDTO connection : connections) {
            if (!edgeKeys.add(connection.id())) {
                throw new BizException(ResultCode.BAD_REQUEST, "连线 id 重复：" + connection.id());
            }
            if (!nodeKeys.contains(connection.fromNodeId())) {
                throw new BizException(ResultCode.BAD_REQUEST,
                        "连线 " + connection.id() + " 的起点节点不存在：" + connection.fromNodeId());
            }
            if (!nodeKeys.contains(connection.toNodeId())) {
                throw new BizException(ResultCode.BAD_REQUEST,
                        "连线 " + connection.id() + " 的终点节点不存在：" + connection.toNodeId());
            }
        }
    }

    private CanvasNode toNodeEntity(Long projectId, CanvasNodeDTO dto) {
        CanvasNode node = new CanvasNode();
        node.setProjectId(projectId);
        node.setNodeKey(dto.id());
        node.setType(dto.type());
        node.setTitle(dto.title());
        node.setPosX(dto.position().x());
        node.setPosY(dto.position().y());
        node.setWidth(dto.width());
        node.setHeight(dto.height());
        node.setMetadata(writeMetadata(dto.metadata()));
        return node;
    }

    private CanvasEdge toEdgeEntity(Long projectId, CanvasConnectionDTO dto) {
        CanvasEdge edge = new CanvasEdge();
        edge.setProjectId(projectId);
        edge.setEdgeKey(dto.id());
        edge.setFromNodeKey(dto.fromNodeId());
        edge.setToNodeKey(dto.toNodeId());
        return edge;
    }

    /** 复制用：只保留业务字段，清掉主键，让 MyBatis-Plus 重新生成雪花 ID。 */
    private CanvasNode detachNode(CanvasNode source) {
        CanvasNode node = new CanvasNode();
        node.setNodeKey(source.getNodeKey());
        node.setType(source.getType());
        node.setTitle(source.getTitle());
        node.setPosX(source.getPosX());
        node.setPosY(source.getPosY());
        node.setWidth(source.getWidth());
        node.setHeight(source.getHeight());
        node.setMetadata(source.getMetadata());
        return node;
    }

    private CanvasEdge detachEdge(CanvasEdge source) {
        CanvasEdge edge = new CanvasEdge();
        edge.setEdgeKey(source.getEdgeKey());
        edge.setFromNodeKey(source.getFromNodeKey());
        edge.setToNodeKey(source.getToNodeKey());
        return edge;
    }

    private ProjectDetailVO toDetail(CanvasProject project, List<CanvasNode> nodes, List<CanvasEdge> edges) {
        return new ProjectDetailVO(
                project.getId(),
                project.getTitle(),
                project.getVersion(),
                project.getBackgroundMode(),
                isTrue(project.getShowImageInfo()),
                toViewportVO(project),
                nodes.stream().map(this::toNodeVO).toList(),
                edges.stream().map(this::toConnectionVO).toList(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    private CanvasViewportVO toViewportVO(CanvasProject project) {
        return new CanvasViewportVO(project.getViewportX(), project.getViewportY(), project.getViewportK());
    }

    private CanvasNodeVO toNodeVO(CanvasNode node) {
        return new CanvasNodeVO(
                node.getNodeKey(),
                node.getType(),
                node.getTitle(),
                new CanvasNodeVO.Position(node.getPosX(), node.getPosY()),
                node.getWidth(),
                node.getHeight(),
                readMetadata(node.getMetadata())
        );
    }

    private CanvasConnectionVO toConnectionVO(CanvasEdge edge) {
        return new CanvasConnectionVO(edge.getEdgeKey(), edge.getFromNodeKey(), edge.getToNodeKey());
    }

    private String writeMetadata(JsonNode metadata) {
        if (metadata == null || metadata.isNull()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            // 已经解析成 JsonNode 的对象再序列化几乎不可能失败，走到这里说明内存里的树有问题
            throw new BizException(ResultCode.BAD_REQUEST, "节点 metadata 无法序列化");
        }
    }

    private JsonNode readMetadata(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return null;
        }
        try {
            return objectMapper.readTree(metadata);
        } catch (JsonProcessingException e) {
            // 库里的数据坏了属于服务端问题，用 IllegalStateException 走 500，并且把原文带进日志
            throw new IllegalStateException("节点 metadata 不是合法 JSON：" + metadata, e);
        }
    }

    private boolean isTrue(Integer flag) {
        return flag != null && flag == 1;
    }

    private String trimTitle(String title) {
        String value = title.trim();
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH);
    }
}
