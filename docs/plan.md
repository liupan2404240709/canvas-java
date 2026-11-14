# 无限画布 Java 后端改写规划

> 目标：把现有「纯前端 + 浏览器本地存储 + 浏览器直连模型中转」的结构，改写成 Spring Boot + MySQL + Redis + MyBatis-Plus 的前后端分离项目，用于学习与简历。

## 进度

| 阶段 | 状态 | 代码 | 文档 |
| --- | --- | --- | --- |
| 0 环境与骨架 | ✅ 已完成并通过本机实测 | `canvas-java/` | [stage-00-scaffold](./stage-00-scaffold/README.md)（[设计](./stage-00-scaffold/design.md) / [接口](./stage-00-scaffold/api.md) / [**代码技术说明 12 篇**](./stage-00-scaffold/tech/README.md)） |
| 1 账号体系 | ✅ 已完成并通过本机全链路实测 | `src/main/java/.../security/`、`service/impl/AuthServiceImpl` | [stage-01-auth](./stage-01-auth/README.md)（[设计](./stage-01-auth/design.md) / [接口](./stage-01-auth/api.md) / [**代码技术说明 12 篇**](./stage-01-auth/tech/README.md)） |
| 2 画布项目 | 待开始 | — | — |
| 3 文件与素材库 | 待开始 | — | — |
| 4 提示词库 | 待开始 | — | — |
| 5 渠道与模型配置 | 待开始 | — | — |
| 6 生成任务 | 待开始 | — | — |
| 7 对话助手 | 待开始 | — | — |
| 8 工程化与部署 | 待开始 | — | — |
| 9 前端对接 | 待开始 | — | — |

## 一、先看清现状：这个项目的"后端"到底是什么

| 现有部分 | 位置 | 实际技术 | 处理方式 |
| --- | --- | --- | --- |
| 本地代理 | `canvas-proxy/index.js`（约 150 行） | Node `http` + `fetch` 流式转发 + 宽松 CORS | **重写**，Java `WebClient` 流式转发，约 1 天 |
| Canvas Agent | `canvas-agent/src`（约 4700 行） | Express + 子进程拉起 Codex CLI + MCP 协议 + SSE + 本地 Skills 文件 | **不整体重写**，成本极高且不是业务后端；阶段 7 用 LangChain4j 自研简化版 |
| 模型调用 | `web/src/services/api/image.ts`、`video.ts`、`audio.ts`（约 1500 行） | 浏览器 axios/fetch 直连 OpenAI 兼容接口，Key 存 localStorage | **搬到后端**，这是简历最值钱的部分 |
| 业务数据 | `localforage` / IndexedDB（画布、素材、提示词缓存、渠道配置） | 纯浏览器持久化，无用户概念、无云同步 | **搬到 MySQL**，引入用户体系 |

结论：真正值得改写成 Java 后端的是**业务数据持久化 + 生成任务编排 + 流式推送**，而不是那个跑 Codex 的本地伴生进程。改写后的架构升级故事是：

> 纯前端工作台（数据在浏览器、Key 在浏览器、请求由浏览器直发三方）→ 前后端分离（账号体系、云端持久化、服务端任务队列、SSE 实时进度、统一密钥管理）。

这个故事在简历上比"我把 Node 翻译成 Java"强得多。

## 二、技术选型

| 项 | 选择 | 说明 |
| --- | --- | --- |
| JDK | 17（LTS） | Spring Boot 3.x 最低要求，生态最稳 |
| 框架 | Spring Boot 3.3.x | Web MVC（不用 WebFlux，MyBatis 本身阻塞） |
| ORM | MyBatis-Plus 3.5.x | 分页插件、逻辑删除、乐观锁、代码生成器 |
| 数据库 | MySQL 8.0 | utf8mb4，JSON 字段存生成参数 |
| 缓存 | Redis 7 + Lettuce | Token、热点缓存、幂等键、限流、任务队列 |
| 鉴权 | Spring Security + JWT（jjwt） | 简历关键词，别用 Sa-Token 图省事 |
| 接口文档 | SpringDoc OpenAPI 3 | 配合 Apifox 调试 |
| 文件存储 | 本地磁盘 + 静态映射，后接 MinIO | 先用抽象接口 `FileStorage`，实现可替换 |
| 异步 | `@Async` + `ThreadPoolTaskExecutor`，进阶 Redis Stream | 先生成任务表 + 线程池，再换队列 |
| 流式 | `SseEmitter`（MVC） | 注意超时、断连清理、多实例需 Redis Pub/Sub |
| 部署 | Dockerfile + docker-compose（mysql/redis/minio） | 阶段 8 完成 |

工程结构（先单模块，别一上来拆 multi-module）。实际工程在**独立仓库** `D:\project\canvas-java`，与前端 `infinite-canvas-main` 平级：

```
canvas-java/
├── pom.xml
├── docker-compose.yml
├── docs/            # 各阶段设计 / 接口 / 说明文档
└── src/main/java/com/infinitecanvas/canvas/
    ├── CanvasApplication.java
    ├── common/        # Result、异常、枚举、常量、工具
    ├── config/        # Security、MyBatis、Redis、CORS、线程池
    ├── controller/    # 只做参数校验与编排
    ├── service/       # 业务接口 + impl
    ├── mapper/        # MyBatis-Plus Mapper
    ├── entity/        # 与表一一对应
    ├── dto/           # 入参
    ├── vo/            # 出参（字段名对齐前端 types/）
    └── infra/         # 三方调用、文件存储、SSE、队列
```

**字段契约**：所有出参直接用前端已有的 camelCase 命名（参考 `web/src/types/canvas.ts`），时间用 ISO8601 字符串，ID 用字符串（雪花 ID 转 String），这样阶段 9 对接前端时几乎不用改页面代码。

## 三、数据库设计（按阶段落地，先给全貌）

| 模块 | 表 | 关键字段 |
| --- | --- | --- |
| 用户 | `t_user` | id, username, email, password_hash, nickname, avatar_url, status, created_at, updated_at, deleted |
| 画布 | `t_canvas_project` | id, user_id, title, cover_url, node_count, viewport_x/y/k, background, version, created_at, updated_at, deleted |
| 画布 | `t_canvas_node` | id, project_id, node_key, type, title, x, y, width, height, metadata_json, sort |
| 画布 | `t_canvas_connection` | id, project_id, from_node_id, to_node_id |
| 文件 | `t_file` | id, user_id, storage_key, url, mime_type, bytes, sha256, biz_type, created_at |
| 素材 | `t_asset` | id, user_id, kind(text/image/video/audio), title, cover_url, source, note, file_id, width, height, bytes, mime_type, duration_ms, tags_json, created_at, updated_at, deleted |
| 提示词 | `t_prompt_source` | id, user_id, name, url, homepage, enabled, last_success_at, last_error, item_count |
| 提示词 | `t_prompt` | id, source_id, title, prompt, description, category, tags_json, reference_images_json, source_url, created_at |
| 模型配置 | `t_channel` | id, user_id, name, base_url, api_key_cipher, enabled, sort |
| 模型配置 | `t_model` | id, channel_id, name, capability(text/image/video/audio), config_json, enabled |
| 生成 | `t_generation_task` | id, user_id, project_id, node_key, type, gen_mode, channel_id, model, prompt, params_json, refs_json, status, progress, result_json, error_msg, provider_task_id, attempt, started_at, finished_at, created_at |
| 对话 | `t_conversation` | id, user_id, project_id, title, model, status, last_message_at, created_at |
| 对话 | `t_message` | id, conversation_id, thread_id, turn_id, item_id, role, content_json, status, token_in, token_out, created_at |
| 计量 | `t_api_usage` | id, user_id, task_id, provider, model, type, image_count, token_in, token_out, cost_ms, created_at |

几个设计要点（都是面试可讲的点）：

1. **画布保存**：节点、连线独立表作为唯一数据源，保存走「事务 + 全量 diff 合并 + `version` 乐观锁」，防多标签页互相覆盖。
2. **读取加速**：整份画布快照写 Redis（`canvas:snapshot:{projectId}`），Cache-Aside，保存后删缓存，避免每次打开都查几百行。
3. **文件秒传**：上传先算 sha256，Redis/`t_file` 命中则直接复用，不重复落盘。
4. **API Key**：AES-GCM 加密后入库，密钥放环境变量，任何日志与出参都不回显明文。
5. **生成任务**：`status` 状态机 `PENDING → RUNNING → SUCCESS/FAILED/CANCELED`，配 `attempt` 重试次数，`idempotency_key` 防重复提交。

## 四、接口规划（统一前缀 `/api/v1`）

响应体统一 `{ code, message, data, traceId }`；分页统一 `{ page, pageSize, total, list }`。

| 页面/模块 | 接口 |
| --- | --- |
| 健康 | `GET /health`（返回 DB、Redis 连通性） |
| 账号 | `POST /auth/register`、`POST /auth/login`、`POST /auth/refresh`、`GET /auth/me`、`POST /auth/logout` |
| 首页项目 | `GET /canvas/projects`、`POST /canvas/projects`、`PUT /canvas/projects/{id}`、`DELETE /canvas/projects/{id}`、`POST /canvas/projects/{id}/duplicate`、`GET /canvas/projects/{id}/export`、`POST /canvas/projects/import` |
| 画布 | `GET /canvas/projects/{id}/snapshot`、`PUT /canvas/projects/{id}/snapshot`（节点+连线整体保存） |
| 文件 | `POST /files/upload`（multipart）、`POST /files/base64`、`GET /files/{id}`、`DELETE /files/{id}` |
| 素材 | `GET /assets`、`POST /assets`、`PUT /assets/{id}`、`DELETE /assets/{id}`、`POST /assets/cleanup` |
| 提示词 | `GET /prompt-sources`、`POST /prompt-sources`、`PUT /prompt-sources/{id}`、`POST /prompt-sources/{id}/refresh`、`GET /prompts`（搜索/标签/分页） |
| 渠道 | `GET /channels`、`POST /channels`、`PUT /channels/{id}`、`DELETE /channels/{id}`、`POST /channels/{id}/test`、`GET /models` |
| 生成 | `POST /generation/image`、`POST /generation/video`、`POST /generation/audio`、`GET /generation/tasks/{id}`、`POST /generation/tasks/{id}/cancel`、`GET /generation/tasks/{id}/stream`（SSE） |
| 对话 | `GET /conversations`、`POST /conversations`、`GET /conversations/{id}/messages`、`POST /conversations/{id}/messages`、`GET /conversations/{id}/stream`、`DELETE /conversations/{id}` |
| 代理 | `ANY /proxy`（替代 canvas-proxy，流式透传 + CORS） |

## 五、分阶段路线（每阶段：读源码 → 建表 → 写接口 → 验证 → 记笔记）

### 阶段 0　环境与骨架（1–2 天）

- 建 `canvas-java` 工程，`docker-compose` 起 MySQL + Redis。
- 统一 `Result`、`BizException` + `GlobalExceptionHandler`、`@Valid` 校验、请求日志（traceId）。
- MyBatis-Plus 配置（分页插件、逻辑删除、自动填充时间），SpringDoc 打开。
- 学习点：Spring Boot 自动配置、多环境 profile、连接池、MyBatis-Plus 拦截器。
- 验收：`GET /api/v1/health` 返回 `db: up, redis: up`。

### 阶段 1　账号体系（3–4 天）

- `t_user` 建表，注册/登录/刷新/登出，BCrypt 密码，JWT 签发。
- Spring Security 过滤链，无状态会话，`@AuthenticationPrincipal` 取当前用户。
- Refresh Token 存 Redis（可撤销），登出加入黑名单。
- 学习点：Security 过滤链顺序、JWT 结构、`HandlerInterceptor` vs Filter、AOP 记录操作日志。
- 验收：注册 → 登录拿 token → 带 token 访问 `/auth/me` → 登出后 token 失效。

### 阶段 2　画布项目（5–7 天）｜对应首页 + 画布页

- 项目 CRUD、重命名、复制、删除（逻辑删除）、导入导出（JSON）。
- 画布快照保存：节点/连线批量 upsert，事务 + `version` 乐观锁。
- 列表分页 + 关键字搜索 + 按更新时间排序。
- 学习点：MyBatis-Plus `LambdaQueryWrapper`、分页插件、`@Transactional` 失效场景、`@Version`、批量插入、DTO/VO 转换。
- 验收：Postman 建项目 → 存 30 个节点 → 并发保存两次，第二次返回版本冲突 → 重新拉取快照一致。

### 阶段 3　文件与素材库（4–5 天）｜对应素材页

- `FileStorage` 抽象 + 本地实现，multipart 上传、类型/大小校验、sha256 秒传。
- 静态资源映射或下载接口，图片缩略图（可选 Thumbnailator）。
- 素材 CRUD、标签、按 kind/关键字/时间筛选分页、引用计数清理。
- 学习点：Stream 上传、`MultipartFile`、资源映射、缓存目录、SHA-256 去重。
- 验收：同一张图上传两次返回同一 `fileId`；素材列表按标签筛选正确。

### 阶段 4　提示词库（3–4 天）｜对应提示词页

- 提示词源 CRUD，后端拉取第三方 registry JSON，解析入库。
- `@Scheduled` 定时刷新 + Redis 缓存 + 分布式锁防并发重复刷新。
- 搜索：标题/正文 `LIKE` + 标签过滤 + 分页（数据量大再上 FULLTEXT）。
- 学习点：`RestClient`、Jackson 解析树形 JSON、定时任务、缓存穿透/雪崩的基本处理、锁。
- 验收：添加源 → 手动刷新拿到条数 → 搜索命中 → 第二次刷新走缓存，日志可验证。

### 阶段 5　渠道与模型配置（2–3 天）｜对应配置页

- 渠道 CRUD，API Key AES-GCM 加密入库，出参脱敏。
- 模型列表维护（能力分类：text/image/video/audio）。
- `POST /channels/{id}/test` 调 `/models` 做连通性检测。
- 学习点：加解密工具、`@JsonIgnore`/VO 脱敏、`RestClient` 超时配置、异常映射。
- 验收：库里是密文，出参是 `sk-****abcd`，测试连接返回模型列表。

### 阶段 6　生成任务（核心，8–10 天）｜对应生图/生视频页

分三步走，别一次做完：

1. **同步版**：`POST /generation/image` 直接调 provider 返回结果 + 存文件 + 记录 `t_generation_task`。
2. **异步版**：提交即返回 `taskId`，线程池执行，`GET /generation/tasks/{id}` 轮询，含超时与重试。
3. **推送版**：`SseEmitter` 推 `queued/running/progress/succeeded/failed` 事件，支持取消、断线重连；多实例用 Redis Pub/Sub 广播。

- 关键实现：OpenAI 兼容 `/v1/images/generations` 与 `/v1/images/edits`（参考图），视频走「提交 → 拿 provider task id → 轮询」。
- 学习点：线程池参数与拒绝策略、`@Async` 代理失效、SSE 生命周期、幂等键、状态机、超时补偿、Redis 令牌桶限流。
- 验收：前端（或 Apifox）触发一次生图，10 秒内收到 SSE 进度与最终图片 URL；杀掉页面重开仍能查到任务结果。

### 阶段 7　对话助手（6–8 天）｜对应右侧 Agent 面板

- 会话/消息表，历史分页加载，SSE 流式输出（可复用 `SseEmitter` 封装）。
- 上下文裁剪（保留最近 N 轮 + 摘要），token 计量写 `t_api_usage`。
- 工具调用：定义 `canvas_*` 工具，服务端返回 tool_call，前端执行并回传结果（沿用现有 Agent 的协议思路，但不用重写 Codex）。
- 建议用 LangChain4j（你已有经验），比手写 function calling 更省事。
- 学习点：流式响应、`Last-Event-ID` 续传、消息幂等、上下文窗口管理。
- 验收：多轮对话流式输出，刷新后历史完整，工具调用能落到画布节点。

### 阶段 8　工程化与部署（3–4 天）

- Dockerfile 多阶段构建，compose 一键起 mysql/redis/app/nginx。
- 日志按天切割，Actuator + 健康检查，接口限流（Redis + 注解 + AOP）。
- 可选：Prometheus 指标、JMeter 压测首页列表与画布保存。
- 验收：`docker compose up` 后全链路可用，压测 QPS 有数字可写。

### 阶段 9　前端对接（渐进，可选）

- 在 `web/src/services/api/` 增加 `client.ts` 指向 `/api/v1`，vite `proxy` 配 8081。
- 新增登录页与 token 注入，画布/素材/提示词逐个替换数据源（保留原本地实现做降级开关）。
- 建议最后统一做，前面全部用 Apifox 验证，避免边改前端边调后端。

## 六、README/简历怎么写（等阶段完成再逐条替换数字）

- 将浏览器本地工作台重构为前后端分离架构，基于 Spring Boot 3 + MyBatis-Plus + MySQL 搭建服务端，设计 N 张核心业务表，支撑画布项目、素材库、提示词库的云端持久化。
- 设计画布快照保存方案：节点/连线独立表 + 事务批量 upsert + 乐观锁版本控制，解决多标签页并发覆盖问题；引入 Redis Cache-Aside 缓存整份快照，读取耗时从 X ms 降至 Y ms。
- 实现图片/视频生成异步任务编排：任务状态机 + 线程池执行 + SSE 实时推送 + 幂等键去重 + 失败重试，支持任务取消与断线重连。
- 基于 Spring Security + JWT + Redis 实现无状态鉴权与可撤销登录态；API Key 采用 AES-GCM 加密入库，出参脱敏。
- 使用 Redis 实现接口限流与文件 SHA-256 秒传，Docker Compose 一键部署。

注意：不要写"重写了 Codex Agent"，那部分你没有做，面试一问就露馅。

## 七、风险与约定

1. **前端契约**：字段名、时间格式、ID 类型必须与 `web/src/types` 对齐，否则阶段 9 会大改。
2. **base64 图片**：现有前端大量 dataURL，后端必须同时支持 multipart 与 base64，并放开 Nginx 请求体大小。
3. **SSE**：Nginx 要关 `proxy_buffering`，超时时间要大于最长生成时长（生视频可能几分钟）。
4. **别过早微服务化**：单模块 + 清晰分包足够，简历上"模块化单体"比"拆了三个不能独立部署的服务"更好讲。
5. **每阶段结束必须提交 git tag**，方便回溯，也方便简历里写迭代过程。
6. **先跑通再优化**：阶段 6 的同步版能出图就算成功，不要一上来就分布式队列。
