# 阶段 2 技术说明 · 目录

阶段 2 一共 12 篇逐模块说明，顺序遵循**一次保存请求的真实路径**：从表结构开始，经过实体映射、事务、并发控制，最后到 SQL。

| 序号 | 文档 | 覆盖的源码 | 一句话 |
| --- | --- | --- | --- |
| 01 | [三张表的关系与字段设计](./01-表结构设计.md) | `db/schema.sql` | 为什么节点不塞进项目表的 JSON 字段 |
| 02 | [实体与 ORM 映射](./02-实体与ORM映射.md) | `entity/CanvasProject` `CanvasNode` `CanvasEdge` | `@Version`、`@TableLogic`、TINYINT 踩坑 |
| 03 | [快照全量替换](./03-快照全量替换.md) | `CanvasProjectServiceImpl#saveSnapshot` | 为什么删了重插，而不是逐条比对 |
| 04 | [乐观锁与并发保存](./04-乐观锁与并发保存.md) | `@Version` + `OptimisticLockerInnerInterceptor` | 两个窗口同时保存，谁的数据留下 |
| 05 | [逻辑删除与回收站](./05-逻辑删除与回收站.md) | `CanvasProjectMapper#selectTrash/restore` | 自动过滤救了你，也坑了你 |
| 06 | [批量插入与 XML Mapper](./06-批量插入与XMLMapper.md) | `CanvasNodeMapper.xml` | 30 个节点一条 SQL 发出去 |
| 07 | [分页与关键字搜索](./07-分页与搜索.md) | `CanvasProjectServiceImpl#list` | 页码越界、排序不稳、`like` 失效 |
| 08 | [DTO / VO 分层](./08-DTO与VO分层.md) | `dto/*` `vo/*` | 为什么入参和出参不复用一个类 |
| 09 | [归属校验与越权防护](./09-归属校验与越权.md) | `requireProject` | 为什么返回 404 而不是 403 |
| 10 | [导入导出格式设计](./10-导入导出格式.md) | `ProjectExportVO` `ImportProjectRequest` | 为什么要有 `formatVersion` |
| 11 | [自动填充与时间戳](./11-自动填充与时间戳.md) | `AuditMetaObjectHandler` | `strictUpdateFill` 的坑（本阶段实测踩到） |
| 12 | [一次保存请求的完整链路](./12-完整链路.md) | 全链路 | 面试让你"讲讲这个接口怎么走的" |

## 面试话术总纲

只准备一段话，背这段（覆盖 03~07 的核心）：

> 画布数据我拆成了三张表：项目主表、节点表、连线表。**没有把整份画布塞进一个 JSON 字段**，虽然那样最简单——因为一旦塞进去，就没法做"按节点查询""节点级权限""部分更新"，也没法体现事务和并发控制这些真本事。
>
> 保存快照的语义是**整份替换**：一个事务里先删掉这个项目下的所有节点和连线，再一次性批量插入新的。相比 diff 出增删改，整份替换只有一条代码路径，绝不会出现"用户删掉的节点还留在库里"这种 bug；代价是节点全部换新主键，但前端只认自己生成的 `nodeKey`，所以无感。
>
> 并发用**乐观锁**而不是悲观锁：`t_canvas_project` 上有 `version` 字段，客户端加载画布时拿到 version，保存时带回来。后端做两道校验——先用请求里的 version 和库里的比，不等就直接 409；再靠 `UPDATE ... WHERE version = ?` 兜住两个请求同时通过的窗口，影响行数为 0 就抛异常让整个事务回滚，节点也不会被改脏。为什么不用悲观锁：画布保存是低频操作，加行锁会让"两个窗口同时开着"的体验变成直接阻塞，而乐观锁只是让后来者刷新重试。
>
> 批量插入用的是 MyBatis XML 里的 `<foreach>` 拼一条 `INSERT ... VALUES (...),(...)`，配合 JDBC 的 `rewriteBatchedStatements=true`，30 个节点一次网络往返。如果循环单条 insert 就是 30 次往返，本地看不出来，线上跨机房就是 30 倍延迟。

追问时的补充点：

- 为什么删除用逻辑删除：画布是用户创作内容，误删成本高，回收站是这类产品的标配；恢复时必须手写 UPDATE，因为 `@TableLogic` 会自动给所有 update 加上 `deleted = 0`，永远匹配不到已删除的行。
- 为什么改标题不递增 version：version 保护的是"画布内容"，重命名不影响内容，否则协作时会刷出一堆无意义的冲突。
- 为什么列表不返回节点：20 条画布、每条几百个节点，响应会到几 MB，列表页只需要标题和计数，所以主表冗余了 `node_count` / `connection_count`。
- 分页为什么按 `updated_at DESC, id DESC` 排两个字段：DATETIME 只精确到秒，同一秒内创建的多个画布如果没有第二排序键，翻页会重复或漏数据。

## 阅读顺序建议

- 只想搞懂"版本冲突 409 是怎么来的"：直接看 04。
- 想搞懂"为什么我的 `updateById` 不更新 `updated_at`"：看 11。
- 面试前一天：逐篇看末尾的自检清单，答不上来的回对应小节补。
