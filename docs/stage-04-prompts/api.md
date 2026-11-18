# 阶段 4 接口文档：提示词库

- Base URL：`http://127.0.0.1:8081/api`
- 全部接口需要 `Authorization: Bearer <accessToken>`
- 响应统一为 `{ code, message, data, traceId }`，`code=0` 表示成功
- 时间字段统一 `ISO_LOCAL_DATE_TIME`：`2026-11-18T10:30:00`

---

## 接口总览

| # | 方法 | 路径 | 说明 | 需要登录 |
| --- | --- | --- | --- | --- |
| 1 | GET | `/v1/prompts` | 提示词列表（关键字/标签/分栏/分页） | ✅ |
| 2 | GET | `/v1/prompt-sources` | 源列表（含同步状态） | ✅ |
| 3 | POST | `/v1/prompt-sources` | 新增源 | ✅ |
| 4 | PATCH | `/v1/prompt-sources/{sourceKey}` | 局部更新 | ✅ |
| 5 | DELETE | `/v1/prompt-sources/{sourceKey}` | 删除源（连带提示词） | ✅ |
| 6 | POST | `/v1/prompt-sources/{sourceKey}/refresh` | 同步单源 | ✅ |
| 7 | POST | `/v1/prompt-sources/refresh` | 同步全部启用源 | ✅ |

> 路径里用 `sourceKey`（业务键）而不是数字主键，原因见 [design.md D3](./design.md)。

---

## 1. GET `/v1/prompts`

### 请求参数

| 参数 | 类型 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `keyword` | string | 否 | — | 匹配 `title` / `prompt` / `description`，`%keyword%` 模糊 |
| `tag` | string（可重复） | 否 | — | 标签，多个是**或**关系：`?tag=人像&tag=美食` |
| `category` | string | 否 | 全部 | 分栏 = 源名称；传 `all` 或不传表示全部 |
| `page` | long | 否 | 1 | 小于 1 会被压成 1 |
| `pageSize` | long | 否 | 20 | 上限 100，超出静默压制 |

### 响应

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "items": [
      {
        "id": "e2e-product-shot",
        "title": "极简产品图 · 白底",
        "prompt": "A professional product photograph of {product} ...",
        "description": "适合电商主图，白底、柔光、有接触阴影。",
        "coverUrl": "http://127.0.0.1:8081/api/demo/images/prompt-cover-1.png",
        "referenceImageUrls": [
          "http://127.0.0.1:8081/api/demo/images/prompt-cover-1.png",
          "http://127.0.0.1:8081/api/demo/images/prompt-cover-2.png"
        ],
        "tags": ["产品", "白底", "电商"],
        "preview": "A professional product photograph of {product} ...",
        "createdAt": "2025-03-11T09:20:00",
        "updatedAt": "2025-06-02T11:05:00",
        "author": "canvas-demo",
        "sourceUrl": "https://example.com/prompts/product-shot",
        "sourceId": "src_1a2b3c4d5e6f",
        "category": "本地样例库",
        "githubUrl": "https://example.com/prompts/product-shot",
        "imageMode": "text-to-image",
        "imageModel": "gpt-image-2",
        "imageSize": "1024x1024",
        "imageCount": 2
      }
    ],
    "tags": ["3D", "人像", "排版", "美食", "胶片", "赛博朋克"],
    "categories": ["Banana Prompt Quicker", "本地样例库"],
    "total": 8,
    "page": 1,
    "pageSize": 20
  }
}
```

### 字段说明

| 字段 | 说明 |
| --- | --- |
| `items[].id` | **源内 id**（`prompt_key`），不是数据库主键 |
| `items[].sourceId` | 源业务键，等于源的 `id` |
| `items[].category` | 源名称（前端拿它做分栏） |
| `items[].githubUrl` | `sourceUrl` 为空时回落成源的 `homepage` |
| `items[].coverUrl` | 源没给封面时，取第一张参考图；都没有则为空串 |
| `items[].createdAt/updatedAt` | 源里的时间，解析失败为空串 |
| `tags` | **忽略标签筛选**算出的标签云：勾选「人像」后，「美食」仍然在 |
| `categories` | 启用中的源名称（停用的不出现） |
| `total` | 当前筛选条件下的总条数（不是总源的条数） |

### 排序

`source_id ASC, sort_index ASC, id ASC` —— 先按源分组，源内保持上游数组顺序，最后用主键兜底保证分页不重不漏。

---

## 2. GET `/v1/prompt-sources`

首次调用会给当前用户播种 7 个内置源（幂等）。

```json
{
  "code": 0,
  "data": [
    {
      "id": "banana-prompt-quicker",
      "name": "Banana Prompt Quicker",
      "url": "https://raw.githubusercontent.com/yukkcat/image-prompts/main/dist/sources/banana-prompt-quicker.json",
      "homepage": "https://glidea.github.io/banana-prompt-quicker/",
      "enabled": true,
      "builtIn": true,
      "refreshIntervalMinutes": 30,
      "count": 0,
      "lastSuccessAt": "",
      "lastFetchedAt": "",
      "lastError": ""
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 业务键。内置源 = registry id，自定义源 = `src_` + 12 位十六进制 |
| `builtIn` | `true` 时只能改 `enabled` / `refreshIntervalMinutes`，不能改地址、不能删 |
| `refreshIntervalMinutes` | `0` = 只手动刷新，不参与定时任务 |
| `count` | 上次同步后的条数 |
| `lastSuccessAt` | 最近一次**成功**时间，从未成功为空串 |
| `lastFetchedAt` | 最近一次**尝试**时间（成功失败都更新） |
| `lastError` | 最近一次失败原因，成功后清空 |

---

## 3. POST `/v1/prompt-sources`

### 请求

```json
{
  "name": "我的提示词",
  "url": "https://example.com/prompts.json",
  "homepage": "https://example.com",
  "enabled": true,
  "refreshIntervalMinutes": 30
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `name` | ✅ | ≤100，同一用户下唯一 |
| `url` | ✅ | ≤1024，必须是 http/https 且有 host |
| `homepage` | ❌ | ≤1024，必须是 http/https（为空时不校验） |
| `enabled` | ❌ | 默认 `true` |
| `refreshIntervalMinutes` | ❌ | 0 ~ 10080，默认 30 |

### 响应

同「源列表」里的单个元素。`id` 由后端生成，**不接受客户端传**。

### 错误

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | 40000 | `name` 为空、超长（Bean Validation） |
| 400 | 40040 | `url` / `homepage` 不是合法 http/https |
| 409 | 40940 | 同名源已存在 |

---

## 4. PATCH `/v1/prompt-sources/{sourceKey}`

局部更新：**字段为 `null` 表示不改**，不是"改成空"。

```json
{ "name": "新名字", "enabled": false, "refreshIntervalMinutes": 60 }
```

| 字段 | 说明 |
| --- | --- |
| `name` | 改名时校验同用户唯一 |
| `url` | 换地址；下一次同步才生效，不会立刻清空已有提示词 |
| `homepage` | 改主页 |
| `enabled` | 停用后：不参与定时同步，也不出现在 `GET /prompts` 的结果和分栏里（数据保留） |
| `refreshIntervalMinutes` | 改调度间隔 |

### 内置源规则

| 改什么 | 结果 |
| --- | --- |
| `enabled` / `refreshIntervalMinutes` | ✅ 允许 |
| `name` / `url` / `homepage` | ❌ `409 / 40941` |

---

## 5. DELETE `/v1/prompt-sources/{sourceKey}`

- 逻辑删除源（`deleted=1`）；
- **物理删除**它下面的全部提示词；
- 让该用户的标签缓存失效。

响应：`{"code":0,"data":null}`

| HTTP | code | 场景 |
| --- | --- | --- |
| 404 | 40440 | 源不存在 / 不属于自己 |
| 409 | 40941 | 试图删内置源 |

---

## 6. POST `/v1/prompt-sources/{sourceKey}/refresh`

### 请求参数

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `force` | boolean | false | true 时忽略 `ETag` 和内容摘要，强制重写 |

### 响应（成功）

```json
{
  "code": 0,
  "data": {
    "sourceId": "src_1a2b3c4d5e6f",
    "sourceName": "本地样例库",
    "count": 8,
    "lastSuccessAt": "2026-11-18T10:31:02",
    "lastError": "",
    "success": true,
    "skipped": false
  }
}
```

### 响应（内容未变化）

```json
{ "code": 0, "data": { "sourceId": "src_1a2b3c4d5e6f", "sourceName": "本地样例库",
  "count": 8, "lastSuccessAt": "2026-11-18T10:33:10", "lastError": "", "success": true, "skipped": true } }
```

### 响应（上游失败 —— 注意仍是 HTTP 200）

```json
{ "code": 0, "data": { "sourceId": "src_xxx", "sourceName": "坏源",
  "count": 0, "lastSuccessAt": "", "lastError": "上游返回 HTTP 404", "success": false, "skipped": false } }
```

> **为什么失败也是 200**：这是"任务结果"不是"接口调用失败"。前端需要把 `lastError` 原样显示在源卡片上，如果走全局异常处理，用户只会看到一句"服务暂时不可用"。详见 [design.md D9](./design.md)。

### 错误（这些才是真的接口/状态错误）

| HTTP | code | 场景 |
| --- | --- | --- |
| 404 | 40440 | 源不存在 / 不属于自己 |
| 429 | 42920 | 该源正在同步（Redis 锁被占用） |

---

## 7. POST `/v1/prompt-sources/refresh`

同步**全部启用中**的源，串行执行。

```json
{
  "code": 0,
  "data": {
    "results": [
      { "sourceId": "banana-prompt-quicker", "sourceName": "Banana Prompt Quicker", "count": 0,
        "lastSuccessAt": "", "lastError": "请求失败：Connection timed out", "success": false, "skipped": false },
      { "sourceId": "src_1a2b3c4d5e6f", "sourceName": "本地样例库", "count": 8,
        "lastSuccessAt": "2026-11-18T10:31:02", "lastError": "", "success": true, "skipped": true }
    ],
    "total": 8,
    "successCount": 1,
    "failureCount": 7,
    "skippedCount": 1
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `results` | 每个源一条，**单个失败不影响其他** |
| `total` | 所有源 `count` 之和（不是本次新增条数） |
| `successCount` | `success=true` 的个数 |
| `failureCount` | `results.size() - successCount` |
| `skippedCount` | 其中"内容未变化"的个数（包含在 `successCount` 里） |

> 串行同步 7 个源可能需要十几秒。单源读超时 20s，最坏情况会更久；调用方应设置 ≥60s 的超时。

---

## 错误码

| code | HTTP | message | 场景 |
| --- | --- | --- | --- |
| 40040 | 400 | 源地址必须是 http/https 的 JSON 地址 | URL 协议不对 / 无 host |
| 40440 | 404 | 提示词源不存在或已被删除 | 不存在、已删、不属于当前用户 |
| 40940 | 409 | 同名提示词源已存在 | `(user_id, name)` 冲突 |
| 40941 | 409 | 内置源不能修改地址或删除，只能停用 | 改内置源的 name/url/homepage，或删内置源 |
| 42920 | 429 | 该源正在同步中，请稍后再试 | 抢不到 Redis 锁 |

---

## 与前端契约的对照

| 前端类型/字段 | 后端来源 | 备注 |
| --- | --- | --- |
| `Prompt.id` | `t_prompt.prompt_key` | 源内 id |
| `Prompt.sourceId` | `t_prompt_source.source_key` | 业务键 |
| `Prompt.category` | `t_prompt_source.name` | **源名，不是 id** |
| `Prompt.githubUrl` | `source_url` 或源 `homepage` | 二选一回落 |
| `Prompt.tags` | `tags_json` | 空时返回 `[]`，不会是 null |
| `PromptListResponse.items/tags/categories/total` | `PromptListVO` | 多了 `page/pageSize`，前端忽略即可 |
| `PromptSource.id/name/url/homepage/enabled/builtIn` | `PromptSourceVO` | 一致 |
| `PromptSourceStatus.sourceId/count/lastSuccessAt/lastError` | 合并进 `PromptSourceVO` | 少一次「状态」请求 |
| `PromptSourceRefreshResult` | `PromptRefreshResultVO` | 多了 `skipped` |
| `PromptSourceRefreshSummary` | `PromptRefreshSummaryVO` | 多了 `skippedCount` |

> 多出来的字段前端不读也无害；少字段才会出问题，所以这里只做加法。

---

## 附：用 curl / Swagger 快速验一遍

```bash
# 1. 登录
TOKEN=$(curl -s -X POST http://127.0.0.1:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"account":"demo","password":"demo123456"}' | python -c "import sys,json;print(json.load(sys.stdin)['data']['accessToken'])")

# 2. 看内置源（首次会播种 7 个）
curl -s http://127.0.0.1:8081/api/v1/prompt-sources -H "Authorization: Bearer $TOKEN"

# 3. 建一个本地样例源
curl -s -X POST http://127.0.0.1:8081/api/v1/prompt-sources \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"本地样例库","url":"http://127.0.0.1:8081/api/demo/prompts-sample.json","refreshIntervalMinutes":0}'

# 4. 同步（把 <sourceKey> 换成上一步返回的 id）
curl -s -X POST "http://127.0.0.1:8081/api/v1/prompt-sources/<sourceKey>/refresh" -H "Authorization: Bearer $TOKEN"

# 5. 再同步一次，看 skipped=true
curl -s -X POST "http://127.0.0.1:8081/api/v1/prompt-sources/<sourceKey>/refresh" -H "Authorization: Bearer $TOKEN"

# 6. 搜索 + 标签
curl -s "http://127.0.0.1:8081/api/v1/prompts?keyword=%E9%9C%93%E8%99%B9" -H "Authorization: Bearer $TOKEN"
```

Swagger UI：http://127.0.0.1:8081/api/swagger-ui.html → 「提示词库」「提示词源」两个分组。
