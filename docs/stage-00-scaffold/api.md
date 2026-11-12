# 接口文档：阶段 0

- **服务地址**：`http://127.0.0.1:8081`
- **统一前缀**：`/api`（由 `server.servlet.context-path` 提供）
- **接口版本**：`/v1`
- **在线文档**：`http://127.0.0.1:8081/api/swagger-ui.html`
- **OpenAPI JSON**：`http://127.0.0.1:8081/api/v3/api-docs`

## 1. 通用约定

| 项 | 约定 |
| --- | --- |
| 请求编码 | UTF-8 |
| 请求体格式 | `application/json`（文件上传除外，后续用 `multipart/form-data`） |
| 时间格式 | `yyyy-MM-dd'T'HH:mm:ss`（秒级 ISO-8601，如 `2026-11-11T20:58:36`），时区 GMT+8 |
| 认证 | 阶段 0 全部匿名。阶段 1 起除 `/v1/health`、`/v1/auth/**` 外都需要 `Authorization: Bearer {accessToken}` |
| 链路标识 | 请求头 `X-Trace-Id` 可选；不传则服务端生成，并在响应头与响应体中同时返回 |
| 跨域 | 开发期放行 `http://localhost:5173`、`http://localhost:4173`，携带 Cookie |

## 2. 统一响应结构

所有接口（包括出错）都返回同一层结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {},
  "traceId": "3f9c1a4d8b7e4c2fa1d0e6b5c4a39217"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | int | 业务码，`0` 表示成功，非 0 见错误码表 |
| `message` | string | 提示文案，可直接展示给用户 |
| `data` | object \| array \| null | 业务数据；失败时为 `null`，字段会被省略（全局 `non_null`） |
| `traceId` | string | 本次请求链路标识，反馈问题时请提供 |

判断成功请用 `code === 0`，不要用 HTTP 状态码，也不要判断 `data` 是否为空。

### 分页结构

列表接口统一返回 `PageResult`：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "page": 1,
    "pageSize": 20,
    "total": 137,
    "list": []
  },
  "traceId": "..."
}
```

入参约定：`page` 从 1 开始，`pageSize` 默认 20，服务端上限 200（超出按 200 处理）。

## 3. 错误码表

| code | HTTP | 含义 | 什么时候出现 |
| --- | --- | --- | --- |
| `0` | 200 | 成功 | — |
| `40000` | 400 | 请求参数有误 | `@Valid` 校验失败、JSON 格式错误 |
| `40100` | 401 | 未登录或登录已过期 | 阶段 1 起：token 缺失/无效 |
| `40300` | 403 | 没有访问权限 | 阶段 1 起：访问他人资源 |
| `40400` | 404 | 资源不存在 | 路径不存在、按 id 查不到 |
| `40900` | 409 | 资源状态冲突 | 乐观锁版本冲突、状态机不允许的操作 |
| `42900` | 429 | 请求过于频繁 | 阶段 8 限流生效 |
| `50000` | 500 | 服务暂时不可用 | 未预期异常，需看日志 |

## 4. 接口清单

| 方法 | 路径 | 说明 | 状态 |
| --- | --- | --- | --- |
| GET | `/api/v1/health` | 健康检查 | ✅ 阶段 0 |

## 5. GET /api/v1/health

### 请求

无参数、无鉴权。

```bash
curl -s http://127.0.0.1:8081/api/v1/health
```

### 响应

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "app": "canvas-server",
    "version": "0.0.1",
    "status": "UP",
    "db": { "status": "UP", "latencyMs": 6 },
    "redis": { "status": "UP", "latencyMs": 1 },
    "checkedAt": "2026-11-11T20:55:31"
  },
  "traceId": "3f9c1a4d8b7e4c2fa1d0e6b5c4a39217"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `data.app` | string | 服务名，取自 `spring.application.name` |
| `data.version` | string | 版本号，取自 `app.version` |
| `data.status` | string | 整体状态：`UP` / `DOWN` |
| `data.db` | object | MySQL 探针，执行 `SELECT 1` |
| `data.redis` | object | Redis 探针，执行 `PING` |
| `data.db.status` / `data.redis.status` | string | `UP` / `DOWN` |
| `data.db.latencyMs` | long | 探测耗时（毫秒） |
| `data.db.error` | string | 失败原因，成功时不返回该字段 |
| `data.checkedAt` | string | 检查时间 |

### 降级示例（Redis 不可用）

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "app": "canvas-server",
    "version": "0.0.1",
    "status": "DOWN",
    "db": { "status": "UP", "latencyMs": 5 },
    "redis": { "status": "DOWN", "latencyMs": 2, "error": "Unable to connect to Redis" },
    "checkedAt": "2026-11-11T21:02:10"
  },
  "traceId": "9a2b7c1d..."
}
```

> 注意：即使 `status` 为 `DOWN`，HTTP 仍是 200，`code` 仍是 0。这是本阶段的刻意设计 —— 它描述的是"服务活着，但依赖有故障"，属于可观测信息而非请求失败。阶段 8 接入 Actuator 后会额外提供 `/actuator/health` 返回 503，供负载均衡摘流使用。

### 不存在的路径

```bash
curl -s http://127.0.0.1:8081/api/v1/not-exist
```

```json
{
  "code": 40400,
  "message": "接口不存在: /v1/not-exist",
  "traceId": "6c1e..."
}
```

HTTP 状态码 404。注意 `message` 里是剥掉 context-path 之后的路径。

## 6. 前端调用示例

```ts
// web/src/services/api/http.ts（阶段 9 落地）
export async function get<T>(path: string): Promise<T> {
  const res = await fetch(`/api${path}`, { headers: { Accept: "application/json" } });
  const body = (await res.json()) as { code: number; message: string; data: T; traceId: string };
  if (body.code !== 0) throw new Error(body.message);
  return body.data;
}

// 用法
const health = await get<{ status: string }>("/v1/health");
```

## 7. 后续接口规划

按页面功能排，打勾表示已实现：

| 模块 | 接口 | 阶段 |
| --- | --- | --- |
| 系统 | `GET /v1/health` ✅ | 0 |
| 账号 | `POST /v1/auth/register`、`POST /v1/auth/login`、`POST /v1/auth/refresh`、`POST /v1/auth/logout`、`GET /v1/auth/me` | 1 |
| 画布项目 | `GET/POST /v1/canvas/projects`、`PUT/DELETE /v1/canvas/projects/{id}`、`POST /v1/canvas/projects/{id}/duplicate` | 2 |
| 画布快照 | `GET/PUT /v1/canvas/projects/{id}/snapshot` | 2 |
| 文件 | `POST /v1/files/upload`、`POST /v1/files/base64`、`GET /v1/files/{id}` | 3 |
| 素材 | `GET/POST /v1/assets`、`PUT/DELETE /v1/assets/{id}` | 3 |
| 提示词 | `GET/POST /v1/prompt-sources`、`POST /v1/prompt-sources/{id}/refresh`、`GET /v1/prompts` | 4 |
| 渠道 | `GET/POST /v1/channels`、`POST /v1/channels/{id}/test`、`GET /v1/models` | 5 |
| 生成 | `POST /v1/generation/image`、`GET /v1/generation/tasks/{id}`、`GET /v1/generation/tasks/{id}/stream` | 6 |
| 对话 | `GET/POST /v1/conversations`、`POST /v1/conversations/{id}/messages`、`GET /v1/conversations/{id}/stream` | 7 |
