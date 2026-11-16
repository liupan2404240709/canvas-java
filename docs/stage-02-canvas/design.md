# 阶段 2 设计文档：画布项目

> 记录"为什么这么做"，包括被否决的方案和它们的代价。功能清单看 [README](./README.md)，接口细节看 [api.md](./api.md)。

## 一、领域模型

```
┌───────────────────────────────┐
│ t_canvas_project              │
│ ├ id (PK, snowflake)          │
│ ├ user_id  ───────────────┐   │
│ ├ title                   │   │
│ ├ background_mode         │   │
│ ├ show_image_info         │   │
│ ├ viewport_x/y/k          │   │
│ ├ node_count              │   │  冗余，列表页用
│ ├ connection_count        │   │
│ ├ version                 │   │  乐观锁
│ └ deleted / timestamps    │   │
└───────────┬───────────────┘   │
            │ 1:N               │  归属（隔离靠 userId）
   ┌────────┴────────┐          │
   │                 │          │
┌──▼──────────────┐ ┌▼───────────────────┐
│ t_canvas_node   │ │ t_canvas_connection│
│ project_id      │ │ project_id         │
│ node_key (唯一) │ │ edge_key (唯一)    │
│ type/title      │ │ from_node_key      │
│ pos_x/pos_y     │ │ to_node_key        │
│ width/height    │ └────────────────────┘
│ metadata (JSON) │
└─────────────────┘
```

**聚合边界**：`t_canvas_project` 是聚合根。删除、事务、并发控制都以它为单位；节点和连线只能通过项目访问，没有独立接口。

## 二、核心流程

### 2.1 保存快照（最关键的一条）

```
PUT /v1/projects/{id}/snapshot
        │
        ▼
① requireProject(userId, id)          查项目 + 校验归属 —— 失败 404
        │
        ▼
② request.version == project.version? —— 失败 409（第一道，快速拒绝）
        │
        ▼
③ validateGraph(nodes, connections)   纯内存：id 唯一 + 引用存在 —— 失败 400
        │
        ▼
④ DELETE t_canvas_node  WHERE project_id = ?
   DELETE t_canvas_connection WHERE project_id = ?
        │
        ▼
⑤ INSERT INTO t_canvas_node (...) VALUES (...),(...)    批量
   INSERT INTO t_canvas_connection (...) VALUES (...)
        │
        ▼
⑥ UPDATE t_canvas_project SET node_count=?, ..., version=version+1
      WHERE id=? AND version=? AND deleted=0            —— 影响行数 0 → 409（第二道）
        │
        ▼
⑦ 返回 { id, version, nodeCount, connectionCount, updatedAt }
```

**②③ 在任何写操作之前**，⑤⑥ 之间不能有其它逻辑——因为它们必须在同一个事务里，任何一步失败都要整体回滚。

### 2.2 删除与恢复

```
DELETE /projects/{id}                POST /projects/{id}/restore
        │                                    │
   requireProject（404）                UPDATE ... SET deleted=0
        │                              WHERE id=? AND user_id=? AND deleted=1
   UPDATE ... SET deleted=1                    │
   WHERE id=? AND deleted=0              影响行数 0 → 404
        │
   节点、连线不动  ←── 恢复出来才有内容
```

### 2.3 复制

```
读源项目 + 读源节点 + 读源连线
        │
新建项目（version=0，counts 从源数据算好）
        │
节点 detachNode()：清主键、保留 node_key 与所有业务字段
连线 detachEdge()：清主键、保留 edge_key 与 from/to
        │
批量插入
```

**为什么 node_key 原样复用**：唯一索引是 `(project_id, node_key)`，跨项目重复不冲突；复用之后连线里的 `from/to` 不用重写。

## 三、决策记录

### D-01 节点用独立表，不塞进项目的 JSON 字段

| 方案 | 优点 | 缺点 |
| --- | --- | --- |
| **独立表（采用）** | 可单节点查询/更新；能做事务、批量写、唯一约束；体现表设计能力 | 保存要拼装/拆解，代码多 |
| 项目表一个 `nodes JSON` | 保存只需一次 UPDATE，代码极少 | 无法节点级操作；并发粒度是整份文档；数据库不校验内部结构 |

**结论**：画布的节点数是"几十到几百"，独立表的额外成本可接受，换来的是完整的教学价值和后续扩展空间（节点级评论、节点级权限都需要独立表）。

**代价**：列表页不能直接从项目表读节点（所以加了冗余计数）。

### D-02 保存用"整份替换"，不做差量

见 [tech/03](../stage-02-canvas/tech/03-快照全量替换.md)。

**被否决的方案**：读全量 → diff 出 add/update/delete 三组 → 分别执行。

**否决理由**：三条代码路径意味着三种出错方式，"删掉的节点残留在库里"是最典型的事故。数据量小时，"少一条路径"比"少写几行 SQL"重要。

**触发重新评估的条件**：节点数稳定超过 5000，或节点主键需要被外部引用。

### D-03 乐观锁，不用悲观锁 / 分布式锁

| 方案 | 冲突时行为 | 适用 |
| --- | --- | --- |
| **乐观锁（采用）** | 后提交者 409，前端刷新 | 冲突少、写频率低 |
| `SELECT ... FOR UPDATE` | 后到者阻塞 | 冲突多、必须串行 |
| Redis 分布式锁 | 后到者等待 | 跨库、跨服务 |

**理由**：画布保存是"用户停手后触发"的低频操作，两个窗口同时编辑是少数情况。加悲观锁会让"同时打开"直接卡住，体验更差。

**代价**：需要前端配合（收到 409 重新拉快照），且有极小的概率让用户重做一次调整。

### D-04 两道版本校验

只有 ② 不够（有竞态窗口），只有 ⑥ 不够（要等删完插完才发现冲突，浪费一次写放大）。

**②负责体验**（快速失败），**⑥负责正确性**（数据库原子裁决）。

### D-05 改标题不递增 version

`version` 是**内容版本**，不是行版本。

**反例后果**：A 在列表页重命名，B 正在编辑同一画布，B 保存时莫名 409——而 B 根本没碰标题。

### D-06 逻辑删除 + 手写回收站 SQL

`@TableLogic` 让删除变 UPDATE、让查询自动过滤，但**恢复必须手写**（内置 update 会被追加 `deleted=0`）。

**被否决的方案**：不用 `@TableLogic`，全部手写 `deleted` 条件。否决理由：容易漏，漏一处就是数据泄漏。

**代价**：回收站的两个方法要写 XML，且以后每个手写 SQL 都要记得加 `deleted=0`。

### D-07 批量插入用 XML 多值 INSERT

见 [tech/06](../stage-02-canvas/tech/06-批量插入与XMLMapper.md)。

**否决 `saveBatch` 的理由**：需要 Service 继承 `ServiceImpl<Mapper, Entity>`，会把接口污染成"半个 ORM 代理"；而且它默认是 JDBC batch，行为依赖 `rewriteBatchedStatements`。

### D-08 出参不复用实体、入参不复用实体

见 [tech/08](../stage-02-canvas/tech/08-DTO与VO分层.md)。

**关键点**：VO 里的 `id` 映射的是 `node_key`，不是数据库主键。数据库主键每次保存都变，**绝不能暴露给前端**。

### D-09 导出用独立 VO，带 `app` + `formatVersion`

见 [tech/10](../stage-02-canvas/tech/10-导入导出格式.md)。

**核心**：内部 VO 可以随便改，对外文件格式不能。多一个类换一个稳定契约。

### D-10 越权返回 404，不返回 403

见 [tech/09](../stage-02-canvas/tech/09-归属校验与越权.md)。

**代价**：用户手误输错 id 时提示"不存在"而不是"没权限"，语义略模糊。

### D-11 分页参数越界静默压边界，不报错

`page=0 → 1`，`size=500 → 100`。

**理由**：分页是展示参数，报 400 会让前端一个手误变白页；写操作参数才应该严格报错。

### D-12 `show_image_info` 用 `Integer` 不用 `Boolean`

TINYINT/BIT 到 Boolean 的映射在不同驱动下行为不一致，且 `Boolean` 为 null 时无法区分"没传"和"false"。VO 层统一转 `Boolean` 出参，转换点只有一个。

## 四、威胁清单

| 威胁 | 场景 | 防护 |
| --- | --- | --- |
| 水平越权 | 改 URL 里的 id 访问别人的画布 | `requireProject` 统一校验，返回 404 |
| 参数伪造归属 | 请求体里塞 `userId` | DTO 里**没有** `userId` 字段，归属只从 token 取 |
| 数据竞争 | 两个窗口同时保存 | `version` 乐观锁，冲突 409 |
| 数据污染 | 连线指向不存在的节点 | `validateGraph` 在写库前拦截 |
| 超大请求 | 一次传 10 万个节点 | `@Size(max=500)` |
| 敏感信息泄漏 | 出参带 `userId` / `deleted` / 主键 | VO 白名单式定义 |
| 文件格式污染 | 导入未来版本的文件 | `formatVersion` 检查 |
| 软删除数据泄漏 | 手写 SQL 忘加 `deleted=0` | 手写 SQL 只有回收站一处，且它就是要查已删 |

**未覆盖**（明确知道但本阶段不做）：

- 画布分享/协作 → 没有权限模型
- 内容审核 → 阶段 8 之后
- 接口限流 → 阶段 8
- 请求体大小上限 → 目前只有节点数限制，没有字节数限制

## 五、性能考量

| 操作 | 实测/估算 | 说明 |
| --- | --- | --- |
| 保存 30 节点 + 29 连线 | 86ms（含 6 条 SQL） | 其中最慢是节点批量插入 22ms |
| 详情（30 节点） | 3 条 SQL（项目 + 节点 + 连线） | 没有 N+1 |
| 列表（20 条） | 1 条 count + 1 条分页 | 冗余计数避免了子查询 |
| 导入（30 节点） | 与保存接近 | 复用批量插入 |

**为什么详情不用 JOIN**：三次单表查询比一次三表 JOIN 更清晰，且节点/连线的行数不固定，JOIN 会产生笛卡尔积放大。

**N+1 的隐患在哪**：如果以后列表项要显示"封面图"，逐个查节点就会变成 N+1。届时的正确做法是批量查（`WHERE project_id IN (...)`）再分组，而不是循环查。

## 六、已知取舍

| 取舍 | 原因 | 后续 |
| --- | --- | --- |
| 导出不含媒体 | 文件存储是阶段 3 | 阶段 3 后导 ZIP |
| 回收站不自动清理 | 需要调度基础设施 | 阶段 8 |
| 搜索不做 `%` 转义 | 影响小、实现成本不低 | 按需 |
| 没有操作日志 | 阶段 2 只做 CRUD | 协作需求时加 |
| `metadata` 不建索引 | 目前没有按 metadata 查询的需求 | 需要时加生成列索引 |
| 单实例部署 | 乐观锁不依赖分布式协调 | 多实例也安全 |

## 七、与其他阶段的接口

| 阶段 | 关系 |
| --- | --- |
| 阶段 1（账号） | 复用 `SecurityUtils.currentUserId()`、`ResultCode` 的 401xx |
| 阶段 3（素材） | 节点里的 `storageKey` 会指向素材；导出功能升级为 ZIP |
| 阶段 6（生成任务） | 生成结果写入节点 `metadata.images`；任务表引用 `node_key` |
| 阶段 7（对话助手） | `chatSessions` 会单独建表，不塞进项目表 |
| 阶段 9（前端对接） | 用 `ProjectDetailVO` 直接铺画布，`nodeKey` 与原前端 id 语义一致 |
