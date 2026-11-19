# 阶段 5 接口文档：渠道与模型配置

- Base URL：`http://127.0.0.1:8081/api`
- 全部接口需要 `Authorization: Bearer <accessToken>`
- 响应统一为 `{ code, message, data, traceId }`，`code=0` 表示成功
- 时间字段统一 `ISO_LOCAL_DATE_TIME`：`2026-11-18T21:30:00`
- **任何接口都不会返回 API Key 的明文或密文**，只有 `apiKeyHint`

---

## 接口总览

| # | 方法 | 路径 | 说明 |
| --- | --- | --- | --- |
| 1 | GET | `/v1/channels` | 渠道列表（含各自模型） |
| 2 | GET | `/v1/channels/presets` | 国产厂商预设 |
| 3 | GET | `/v1/channels/{channelKey}` | 渠道详情 |
| 4 | POST | `/v1/channels` | 新建渠道 |
| 5 | PUT | `/v1/channels/{channelKey}` | 保存配置（null = 不改） |
| 6 | PATCH | `/v1/channels/{channelKey}/enabled` | 启停 |
| 7 | DELETE | `/v1/channels/{channelKey}` | 删除渠道 |
| 8 | POST | `/v1/channels/probe` | 探测草稿配置（不落库） |
| 9 | POST | `/v1/channels/{channelKey}/test` | 测试已存渠道（结果落库） |
| 10 | GET | `/v1/models` | 跨渠道可用模型 |

> 路径用业务键 `channelKey`（形如 `ch_ab12cd34ef56`）而不是数字主键，原因见 [design.md D4](./design.md)。
> `/presets` 与 `/probe` 是字面路径，优先级高于 `/{channelKey}`，不会冲突。

---

## 错误码

| code | HTTP | 含义 | 前端建议动作 |
| --- | --- | --- | --- |
| 40050 | 400 | 接口地址不是 http/https | 高亮 Base URL 输入框 |
| 40051 | 400 | API Key 为空 | 高亮 Key 输入框 |
| 40052 | 400 | 模型能力非法（只能是 image/video/text/audio） | 下拉框重选 |
| 40450 | 404 | 渠道不存在 / 不属于当前用户 / 已删除 | 从列表移除该行 |
| 50010 | 500 | Key 解密失败（服务端密钥已更换） | 提示"请重新填写 API Key" |

> 探测失败**不走**错误码，返回 `code=0` + `data.ok=false`，见 D13。

---

## 1. GET `/v1/channels`

### 响应

```json
{
  "code": 0,
  "message": "success",
  "data": [
    {
      "id": "ch_ab12cd34ef56",
      "name": "阿里云百炼",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "apiKeyHint": "sk-a****wxyz",
      "apiFormat": "openai",
      "enabled": true,
      "sortIndex": 0,
      "lastTestAt": "2026-11-18T21:30:12",
      "lastTestOk": true,
      "lastTestError": "",
      "models": [
        { "name": "qwen-image-3.0", "capability": "image", "enabled": true, "sortIndex": 0 }
      ]
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | 业务键，等价前端的 `channelId` |
| `apiKeyHint` | 脱敏提示，**永远不含明文** |
| `lastTestOk` | `true` 通过 / `false` 失败 / **字段缺失** = 从未测试（Jackson 配了 `non_null`） |
| `lastTestError` | 失败原因；成功或未测为空串 |
| `models[].script` | 自定义脚本，无则字段缺失 |

---

## 2. GET `/v1/channels/presets`

### 响应

```json
{
  "code": 0,
  "data": [
    {
      "key": "bailian",
      "name": "阿里云百炼（通义）",
      "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
      "apiFormat": "openai",
      "homepage": "https://bailian.console.aliyun.com",
      "note": "OpenAI 兼容端点。生图与对话都走这里，语音合成走 /audio/speech。",
      "models": [
        { "name": "qwen-image-3.0", "capability": "image" },
        { "name": "qwen-max", "capability": "text" }
      ]
    }
  ]
}
```

预置 6 家：`bailian` / `ark` / `zhipu` / `siliconflow` / `deepseek` / `ollama`。
**返回体里没有 `apiKey` 字段**——预设只是填好的表单，Key 必须用户自己提供。

---

## 3. GET `/v1/channels/{channelKey}`

单条详情，结构与列表元素相同。不存在或不属于当前用户 → 404 / 40450。

---

## 4. POST `/v1/channels`

### 请求

```json
{
  "name": "阿里云百炼",
  "baseUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1",
  "apiKey": "sk-xxxxxxxxxxxxxxxx",
  "apiFormat": "openai",
  "enabled": true,
  "models": [
    { "name": "qwen-image-3.0", "capability": "image" },
    { "name": "qwen-max" }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `name` | 否 | 留空自动取 Base URL 的主机名 |
| `baseUrl` | ✅ | 带不带版本段都行，末尾 `/` 会被去掉 |
| `apiKey` | ✅ | 明文，落库前加密 |
| `apiFormat` | 否 | `openai`（默认）/ `gemini` |
| `enabled` | 否 | 默认 `true` |
| `models[].capability` | 否 | 留空则**按模型名推断**（见 [tech/05](./tech/05-模型能力推断.md)） |
| `models[].script` | 否 | 自定义脚本 |

模型名会被**去空、去重、按顺序赋 `sortIndex`**。

### 响应

`200` + 完整 `ChannelVO`。响应体中搜不到明文 Key。

### 错误

`400` / 40050 · 40051 · 40052

---

## 5. PUT `/v1/channels/{channelKey}`

**局部更新语义**：不传 = 不改。

```json
{
  "name": "改过的名字",
  "baseUrl": "https://api.siliconflow.cn/v1",
  "apiKey": "",
  "apiFormat": "openai",
  "enabled": true,
  "sortIndex": 2,
  "models": [{ "name": "Qwen/Qwen-Image" }]
}
```

| 字段 | 不传 | 传空值 | 说明 |
| --- | --- | --- | --- |
| `name` | 不改 | 空串 → 回落成主机名 | |
| `baseUrl` | 不改 | 不允许空串（40050） | 变化会清空测试状态 |
| `apiKey` | **保持原 Key** | `""` 同样表示保持原值 | 换 Key 会清空测试状态 |
| `apiFormat` | 不改 | — | 变化会清空测试状态 |
| `enabled` | 不改 | — | |
| `sortIndex` | 不改 | — | |
| `models` | **不动模型列表** | `[]` = **清空所有模型** | 按名字差量增删改 |

`apiKey` 与 `models` 的"空"语义不同，是这一阶段最容易传错的地方，见 [design.md D19](./design.md)。

### 响应

`200` + 最新 `ChannelVO`。

---

## 6. PATCH `/v1/channels/{channelKey}/enabled?value=true`

快捷启停，避免为一个布尔值传整份配置。

| 参数 | 位置 | 说明 |
| --- | --- | --- |
| `channelKey` | path | 渠道业务键 |
| `value` | query | `true` 启用 / `false` 停用 |

响应 `200` + `ChannelVO`。停用后该渠道的模型立即从 `/v1/models` 消失。

---

## 7. DELETE `/v1/channels/{channelKey}`

逻辑删除渠道，**物理删除**其下所有模型（同事务）。

响应 `200` + `data: null`。重复删除 → 404 / 40450。

---

## 8. POST `/v1/channels/probe`

探测一组配置，**不落库**。用于"填完先测一下再保存"。

### 请求

```json
{
  "channelKey": "ch_ab12cd34ef56",
  "baseUrl": "https://api.siliconflow.cn/v1",
  "apiKey": "sk-xxxxxxxx",
  "apiFormat": "openai"
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `baseUrl` | ✅ | |
| `apiKey` | 否 | 留空时，若同时传了 `channelKey`，用库里已存的 Key |
| `channelKey` | 否 | 决定 `models[].exists` 的比对基准，也用于取已存 Key |
| `apiFormat` | 否 | 默认 `openai` |

> 既没传 `apiKey` 又没传 `channelKey` → 400 / 40051。

### 响应

```json
{
  "code": 0,
  "data": {
    "ok": true,
    "message": "连接成功，发现 3 个模型",
    "models": [
      { "name": "Qwen/Qwen-Image", "capability": "image", "exists": false },
      { "name": "Qwen/Qwen-VL", "capability": "text", "exists": false }
    ],
    "latencyMs": 312,
    "httpStatus": 200
  }
}
```

失败时同样是 `code=0`：

```json
{
  "code": 0,
  "data": {
    "ok": false,
    "message": "鉴权失败（HTTP 401）：API Key 不正确，或该 Key 没有 /models 权限",
    "models": [],
    "latencyMs": 0,
    "httpStatus": 401
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `ok` | 是否连通 |
| `message` | 已翻译成人话；**不含上游响应体**（见 D14） |
| `models[].capability` | 后端按名字推断的初始分类 |
| `models[].exists` | 该渠道里是否已有同名模型 |
| `latencyMs` | 0 表示没连上（DNS / 超时 / 地址非法） |
| `httpStatus` | 0 表示没连上 |

---

## 9. POST `/v1/channels/{channelKey}/test`

测试已保存渠道，响应结构与 `probe` 完全一致，唯一区别是**结果会落库**：
`last_test_at` / `last_test_ok` / `last_test_error`。

成功时会**显式把 `last_test_error` 写成 NULL**（否则上一次的失败原因会永远留着，见 D20）。

---

## 10. GET `/v1/models`

跨渠道的可用模型，供下拉框使用。只返回**启用渠道**下的**启用模型**。

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `capability` | 否 | `image` / `video` / `text` / `audio`；不传返回全部 |

### 响应

```json
{
  "code": 0,
  "data": [
    {
      "id": "ch_ab12cd34ef56::qwen-image-3.0",
      "channelId": "ch_ab12cd34ef56",
      "channelName": "阿里云百炼",
      "name": "qwen-image-3.0",
      "capability": "image"
    }
  ]
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | `channelKey::modelName`，与前端既有约定逐字一致 |
| `channelId` | 方便前端按渠道分组 |

排序：**渠道顺序优先，其次渠道内 `sortIndex`**。

### 错误

`capability` 传非法值 → `400` / 40052。

---

## 附：一次完整的"新建并验证"请求序列

```
POST /v1/channels/probe         # 先测，apiKey 明文只在这一次出现
  → data.ok=true, models=[...]

POST /v1/channels               # 再存，服务端加密
  → data.id = "ch_ab12cd34ef56"

GET  /v1/models?capability=image
  → [{"id": "ch_ab12cd34ef56::qwen-image-3.0", ...}]

POST /v1/channels/ch_ab12cd34ef56/test   # 之后随时复测
  → data.ok=true，同时落库 lastTestOk
```
