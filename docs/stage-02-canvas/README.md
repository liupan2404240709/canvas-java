# 阶段 2：画布项目（完成）

> 目标：把"画布项目 + 节点 + 连线"这套数据搬上服务端，做出**增删改查 + 快照保存 + 乐观锁 + 回收站 + 导入导出**。
> 这一阶段是整个项目的核心业务，后面的素材、提示词、生成任务都会复用这里定下的分层和事务写法。

📖 技术说明（逐模块 + 面试考点）在 [`tech/`](./tech/README.md)，建议先看 [03 快照全量替换](./tech/03-快照全量替换.md) 和 [04 乐观锁](./tech/04-乐观锁与并发保存.md)。

## 一、本阶段完成了什么

| 能力 | 说明 |
| --- | --- |
| 画布 CRUD | 创建、详情、局部更新、逻辑删除、复制 |
| 快照保存 | 整份替换节点与连线，事务 + 乐观锁，并发保存返回 409 |
| 列表 | 分页 + 标题模糊搜索 + 按更新时间倒序 |
| 回收站 | 逻辑删除后可查、可恢复 |
| 导入导出 | JSON 格式，带 `app` 与 `formatVersion` |
| 归属隔离 | 所有带 id 的接口都校验 `userId`，越权返回 404 |

## 二、交付清单

### 新增源码（24 个 Java + 3 个 XML）

```
entity/
├── CanvasProject.java          项目实体（@Version / @TableLogic / 审计字段填充）
├── CanvasNode.java             节点实体（附属表，无审计字段）
└── CanvasEdge.java             连线实体

mapper/
├── CanvasProjectMapper.java    内置 CRUD + 回收站查询 / 恢复
├── CanvasNodeMapper.java       内置 CRUD + 批量插入
└── CanvasEdgeMapper.java       内置 CRUD + 批量插入

dto/
├── CanvasViewportDTO.java      视口
├── CanvasNodeDTO.java          节点（含嵌套 Position）
├── CanvasConnectionDTO.java    连线
├── CreateProjectRequest.java   创建
├── UpdateProjectRequest.java   局部更新（PATCH 语义）
├── SaveSnapshotRequest.java    保存快照（带 version）
├── CopyProjectRequest.java     复制
└── ImportProjectRequest.java   导入

vo/
├── CanvasNodeVO.java           节点出参（嵌套 Position）
├── CanvasConnectionVO.java     连线出参
├── CanvasViewportVO.java       视口出参
├── ProjectSummaryVO.java       列表项（不含节点）
├── ProjectDetailVO.java        详情（含节点 + 连线 + version）
├── ProjectExportVO.java        导出格式（对外契约）
└── SaveSnapshotVO.java         保存结果（只回 version）

service/
├── CanvasProjectService.java           接口
└── impl/CanvasProjectServiceImpl.java  11 个方法的实现

controller/
└── CanvasProjectController.java        11 个接口

resources/mapper/
├── CanvasProjectMapper.xml     回收站查询 / 恢复 SQL
├── CanvasNodeMapper.xml        节点批量插入
└── CanvasEdgeMapper.xml        连线批量插入
```

### 新增资源

| 文件 | 说明 |
| --- | --- |
| `src/main/resources/db/schema.sql` | 追加 3 张表（`t_canvas_project` / `t_canvas_node` / `t_canvas_connection`） |
| `scripts/stage2-e2e.py` | 20 步全链路验证脚本，可重复执行 |

### 修改的已有文件

| 文件 | 改动 |
| --- | --- |
| `common/api/ResultCode.java` | 新增 `PROJECT_NOT_FOUND(40410)`、`PROJECT_VERSION_CONFLICT(40920)` |
| `common/constant/ApiPath.java` | 新增 `PROJECTS = /v1/projects` |
| `common/exception/GlobalExceptionHandler.java` | 新增 `MethodArgumentTypeMismatchException` → 400（否则 `/projects/abc` 会变 500） |
| `config/AuditMetaObjectHandler.java` | `strictUpdateFill` → `setFieldValByName`（修 `updated_at` 不刷新的问题） |
| `application.yml` | 显式配置 `mybatis-plus.mapper-locations` |
| `application-dev.yml` | SQL 日志 `StdOutImpl` → `Slf4jImpl`（进日志文件、带 traceId） |

## 三、启动步骤

前置：MySQL 与 Redis 容器已启动（阶段 0 配好）。

```powershell
cd D:\project\canvas-java
docker compose up -d          # 已启动可跳过
docker compose ps             # 两个 healthy 再往下

mvn spring-boot:run
```

打开画布表结构不需要手动执行 SQL——`schema.sql` 在启动时自动执行，`CREATE TABLE IF NOT EXISTS` 保证重复启动安全。

启动成功标志：

```
Tomcat started on port 8081 (http) with context path '/api'
Started CanvasApplication in 4.3 seconds
```

## 四、验收：手工跑一遍

先拿 token（登录接口在阶段 1）：

```powershell
curl.exe -s -X POST http://127.0.0.1:8081/api/v1/auth/login `
  -H "Content-Type: application/json" `
  -d "{\"account\":\"canvas_demo\",\"password\":\"canvas1234\"}"
```

记下 `data.accessToken`，下面用 `$T` 代指。

### 1. 创建画布

```powershell
curl.exe -s -X POST http://127.0.0.1:8081/api/v1/projects `
  -H "Content-Type: application/json" -H "Authorization: Bearer $T" `
  -d "{\"title\":\"我的第一张画布\"}"
```

期望：`code=0`，`data.id` 是**字符串**，`nodeCount=0`。

### 2. 保存 30 个节点的快照

```powershell
curl.exe -s -X PUT http://127.0.0.1:8081/api/v1/projects/{id}/snapshot `
  -H "Content-Type: application/json" -H "Authorization: Bearer $T" `
  -d "{\"version\":0,\"nodes\":[{\"id\":\"n_1\",\"type\":\"image\",\"title\":\"图1\",\"position\":{\"x\":0,\"y\":0},\"width\":320,\"height\":240,\"metadata\":{\"prompt\":\"日落\"}}],\"connections\":[]}"
```

期望：`version` 从 0 变 1，`nodeCount=1`。

### 3. 再用旧版本号保存一次（应冲突）

把上面请求原样再发一次（`version` 还是 0）。

期望：`HTTP 409` / `code=40920` / `画布已被其他窗口修改，请刷新后重试`。

### 4. 删除 → 回收站 → 恢复

```powershell
curl.exe -s -X DELETE http://127.0.0.1:8081/api/v1/projects/{id} -H "Authorization: Bearer $T"
curl.exe -s http://127.0.0.1:8081/api/v1/projects/trash -H "Authorization: Bearer $T"
curl.exe -s -X POST http://127.0.0.1:8081/api/v1/projects/{id}/restore -H "Authorization: Bearer $T"
```

期望：删除后查详情 404；回收站里有；恢复后再查详情，**节点还在**。

### 5. 一键全跑（推荐）

```powershell
python scripts\stage2-e2e.py
```

脚本会跑 20 步并在最后清理测试数据。**注意脚本默认打 8082**（避免和正在运行的 8081 冲突），要打 8081 就把文件开头的 `BASE` 改掉。

## 五、判断验收通过的标准

| 检查项 | 期望 |
| --- | --- |
| 三张表自动创建 | `SHOW TABLES` 有 `t_canvas_project` / `t_canvas_node` / `t_canvas_connection` |
| 保存 30 节点 | `nodeCount=30`、`connectionCount=29`，`version` +1 |
| 重新拉详情 | 节点顺序稳定（`n_0` 在前），`metadata` 是**对象**不是字符串 |
| 旧版本保存 | `409` + `40920` |
| 并发保存 | 一个 200、一个 409，库里数据完整 |
| 改标题 | 返回 200，**`version` 不变** |
| 列表 | 按 `updatedAt` 倒序，不含 `nodes` 字段 |
| `size=500` | 被压到 `100` |
| 越权访问 | `404` + `40410`（不是 403） |
| 删除后查详情 | `404` + `40410` |
| 恢复后 | 节点仍在 |
| 导出再导入 | 新画布，节点数一致，背景/视口一起带过来 |

## 六、实测记录（真实输出）

环境：Docker 里的 MySQL 8.0.46 + Redis 7，应用跑在 8082（8081 被占）。

### 启动与建表

```
Tomcat started on port 8082 (http) with context path '/api'
Started CanvasApplication in 4.3 seconds

SHOW TABLES;
t_canvas_connection
t_canvas_node
t_canvas_project
t_user
```

### 20 步全链路

| # | 操作 | 实际结果 |
| --- | --- | --- |
| 1 | 创建画布 | `code=0`，`version` 隐含 0，`nodeCount=0` |
| 2 | 空详情 | `nodes=0`，`viewport={x:0,y:0,k:1}`，`showImageInfo=false` |
| 3 | 保存 30 节点 29 连线 | `version` 0→1，`nodeCount=30`，`connectionCount=29` |
| 4 | 重新拉详情 | `nodes=30`、`edges=29`，首个节点 `n_0`，`metadata` 完整保留 |
| 5 | 旧版本再保存 | `409` / `40920` |
| 6 | 新版本只存 1 节点 | `version` 2，`nodes=1`、`edges=0`，**视口未传所以保持 0.85 不变** |
| 7 | 重命名 | 200，`version` 仍是 2 |
| 8 | 改背景/视口 | `backgroundMode=dots`，`showImageInfo=true`，`viewport={10,20,1.5}` |
| 9 | 列表 / 搜索 / 上限 | `total=1`；搜"改过名"命中 1 条；`size=500` 压到 `100` |
| 10 | 复制 | 标题「改过名的画布 副本」，`version=0`，节点一起复制 |
| 11 | 导出 | `app=infinite-canvas`，`formatVersion=1` |
| 12 | 导入 | 新 id，`title=从文件导入的画布`，`backgroundMode=dots` 一并还原 |
| 13 | 导入 formatVersion=99 | `400` / `导入文件版本过高（99），当前最多支持 1` |
| 14 | 连线指向不存在节点 | `400` / `连线 e_x 的终点节点不存在：n_99` |
| 15 | 节点 id 重复 | `400` / `节点 id 重复：n_0` |
| 16 | 节点缺 position | `400` / `nodes[0].position: 节点缺少 position` |
| 17 | 节点宽度为负 | `400` / `nodes[0].width: 节点宽度不能为负` |
| 18 | `/projects/abc` | `400` / `参数格式错误: id`（不是 500） |
| 19 | 越权（详情/删除/保存） | 三条都是 `404` / `40410` |
| 20 | 并发保存同一版本 | `[(1, 409, 40920), (2, 200, 0)]` —— 一成一败 |

### 删除 → 回收站 → 恢复

```
删除              HTTP 200 code=0 "success"
删除后查详情       HTTP 404 code=40410
回收站            HTTP 200 条数=1 含本画布=True
恢复              HTTP 200 code=0 "success"
恢复后详情         HTTP 200 nodes=1（节点应还在）
再次恢复          HTTP 404 code=40410
```

### 时间戳

```
创建      createdAt = 2026-11-14T17:19:41
保存后    updatedAt = 2026-11-14T17:19:42
改名后    updatedAt = 2026-11-14T17:19:43
```

### 数据库里长什么样

```sql
SELECT node_key, type, metadata, JSON_EXTRACT(metadata,'$.nested.k') FROM t_canvas_node;
-- n_a  image  {"tags": ["a","b"], "count": 3, "nested": {"k": 1.5}, "prompt": "日落"}   1.5
```

中文用 `CHAR_LENGTH` 校验过：`时间戳验证-改名` 是 8 个字符，`HEX` 与 UTF-8 编码一致（终端里显示 `???` 只是 Windows 控制台编码问题，库里是对的）。

### SQL 日志（同一 traceId 串起来）

```
[...92c6af3c...] CanvasProjectMapper.selectById   Total: 1
[...92c6af3c...] CanvasNodeMapper.delete          Updates: 0
[...92c6af3c...] CanvasEdgeMapper.delete          Updates: 0
[...92c6af3c...] CanvasNodeMapper.insertBatch     Updates: 30   ← 一条 SQL 插 30 行
[...92c6af3c...] CanvasEdgeMapper.insertBatch     Updates: 29
[...92c6af3c...] CanvasProjectMapper.updateById   Updates: 1
    WHERE id=? AND version=? AND deleted=0        ← 乐观锁生效
```

整个请求 86ms（05.516 → 05.602）。

### 本阶段修掉的两个真问题

**① `updated_at` 不会刷新**

`AuditMetaObjectHandler` 原来用 `strictUpdateFill`，它**只在字段为 null 时填充**。而 `updateById(entity)` 的实体是从库里读出来的、`updatedAt` 早有旧值，于是 SQL 里显式写回旧时间；MySQL 的 `ON UPDATE CURRENT_TIMESTAMP` 在列被显式赋值时不触发，`updated_at` 就永远停在创建时间。

改成 `setFieldValByName` 后实测：保存 +1s、改名 +1s，两条路径都对。详见 [tech/11](./tech/11-自动填充与时间戳.md)。

**② `/projects/abc` 返回 500**

`@PathVariable Long id` 类型转换失败抛 `MethodArgumentTypeMismatchException`，原来没有对应的 handler，掉进兜底变 500——看起来像服务挂了。补了 handler 返回 400。

### 一个实现时避开的坑

`copy` 一开始直接复用了原节点实体，插入时撞 `PRIMARY KEY`——因为实体里带着原项目的雪花 id。改成 `detachNode` / `detachEdge` 只拷业务字段、清空主键，让 MP 重新生成。

## 七、本阶段要掌握的知识点

1. **三表模型**：为什么节点不塞进 JSON 字段；`(project_id, node_key)` 唯一索引与最左前缀。
2. **整份替换 vs 差量更新**：各自代价；为什么小数据量下"少一条路径"更重要。
3. **事务边界**：`@Transactional` 放在哪、`rollbackFor` 为什么写、五种失效场景。
4. **乐观锁**：`@Version` 的实现原理、两道校验的分工、冲突为什么不自动重试。
5. **逻辑删除**：`@TableLogic` 改写了哪些方法、为什么恢复必须手写 SQL、唯一索引冲突的三种解法。
6. **批量插入**：`<foreach>` 多值 INSERT、`rewriteBatchedStatements`、应用侧生成主键的好处。
7. **分页**：`ORDER BY` 为什么要有唯一键兜底、`like '%x%'` 什么时候还能用索引。
8. **DTO/VO 分层**：为什么不复用、`JsonNode` 为什么比 `Map` 适合透传。
9. **越权防护**：404 为什么比 403 安全、`Long` 用 `==` 的坑。
10. **自动填充**：`strictUpdateFill` 在 update 场景失效，以及 `ON UPDATE` 什么时候不管用。

## 八、常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动报 `Table 'infinite_canvas.t_canvas_project' doesn't exist` | `spring.sql.init.mode` 不是 `always` | 检查 `application-dev.yml` |
| `Invalid bound statement (not found): ...insertBatch` | XML 没被加载 | 确认 `mybatis-plus.mapper-locations` 和 `resources/mapper/` 路径一致 |
| 保存报 `Duplicate entry for key 'uk_node_project_key'` | 请求里节点 `nodeKey` 重复 | Service 已校验，若仍出现说明绕过校验；查是否两个节点同名 |
| 一直返回 40920 | 前端没更新 version | 保存成功后必须用响应里的 `version` 覆盖本地值 |
| 列表里删掉的画布还在 | 手写 SQL 忘了 `deleted=0` | 检查自定义查询 |
| 恢复接口永远 404 | 用了 `updateById` | 必须手写 UPDATE（见 tech/05） |
| 详情里 `metadata` 是字符串 | 出参直接用了实体 | 转 VO 时 `readTree` |
| 前端拿到 id 精度不对 | 没转字符串 | VO 的 id 上加 `ToStringSerializer` |
| 日志里没有 SQL | 用了 `StdOutImpl` | 换 `Slf4jImpl` |
| `updated_at` 不变 | `strictUpdateFill` | 改 `setFieldValByName`（tech/11） |
| 分页翻页出现重复数据 | 只按 `updated_at` 排序 | 加 `id DESC` |

## 九、已知取舍与后续

| 取舍 | 说明 | 什么时候补 |
| --- | --- | --- |
| 快照整份替换 | 节点主键每次都变；500 节点上限 | 数据量上万时改差量 |
| 导出不含媒体文件 | 只导 JSON，图片节点只有 `storageKey` | 阶段 3 做文件存储后导 ZIP |
| 回收站无自动清理 | 逻辑删除的数据永久保留 | 阶段 8 加定时任务 |
| 搜索不支持 `%` 转义 | 标题含 `%` 会多匹配 | 需要时加 `ESCAPE` |
| 没有节点级权限 | 整张画布共享给协作者是不可能的 | 排到协作/分享需求 |
| `version` 只管内容 | 重命名不递增 | 有意设计 |
| 单实例 | `version` 不依赖分布式锁 | 多实例也安全（靠数据库行锁） |

## 十、下一阶段

**阶段 3：文件与素材库**。会用到本阶段的能力：

- 素材也按"归属用户 + 分页 + 逻辑删除"建模，直接复用这里的写法；
- 图片节点的 `storageKey` 会和素材表关联；
- 导出功能在阶段 3 之后可以升级成带媒体的 ZIP。

规划里的重点：multipart 上传、SHA-256 秒传、存储抽象（本地 / 对象存储）、图片元信息（宽高、字节数）。
