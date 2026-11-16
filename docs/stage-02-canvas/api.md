# 阶段 2 接口文档：画布项目

> 所有接口都在 `/api` 之下（context-path），下文路径省略该前缀。
> 示例数据来自**真实调用**，时间格式为 `yyyy-MM-dd'T'HH:mm:ss`。

## 一、接口总览

| # | 方法 | 路径 | 说明 | 需登录 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/v1/projects` | 创建画布 | ✅ |
| 2 | GET | `/v1/projects` | 画布列表（分页 + 搜索） | ✅ |
| 3 | GET | `/v1/projects/{id}` | 画布详情（含节点与连线） | ✅ |
| 4 | PATCH | `/v1/projects/{id}` | 局部更新（标题/背景/视口） | ✅ |
| 5 | DELETE | `/v1/projects/{id}` | 删除（进回收站） | ✅ |
| 6 | POST | `/v1/projects/{id}/copy` | 复制画布 | ✅ |
| 7 | PUT | `/v1/projects/{id}/snapshot` | 保存快照（整份替换 + 乐观锁） | ✅ |
| 8 | GET | `/v1/projects/{id}/export` | 导出 JSON | ✅ |
| 9 | POST | `/v1/projects/import` | 导入 JSON | ✅ |
| 10 | GET | `/v1/projects/trash` | 回收站列表 | ✅ |
| 11 | POST | `/v1/projects/{id}/restore` | 从回收站恢复 | ✅ |

**全部需要登录**：未登录一律 `401` + `40100`。

## 二、通用约定补充

### 1. 分页参数

| 参数 | 默认 | 约束 | 说明 |
| --- | --- | --- | --- |
| `page` | 1 | `< 1` 按 1 处理 | 页码从 1 开始 |
| `size` | 20 | `> 100` 按 100 处理，`<= 0` 按 20 处理 | **静默压边界，不报错** |

响应：

```json
{
  "code": 0,
  "data": { "page": 1, "pageSize": 10, "total": 1, "list": [ ... ] },
  "traceId": "..."
}
```

### 2. `version` 的语义

- 创建时为 **0**；
- 每成功保存一次快照 **+1**；
- **重命名/改背景/改视口不改变 version**（它只管画布内容）；
- 保存时必须带回来，不匹配返回 `40920`。

### 3. 节点结构（`CanvasNodeVO`）

字段名与前端 `web/src/types/canvas.ts` 的 `CanvasNodeData` **完全一致**：

```json
{
  "id": "n_0",
  "type": "image",
  "title": "节点0",
  "position": { "x": 0.0, "y": 0.0 },
  "width": 320.0,
  "height": 240.0,
  "metadata": { "prompt": "日落", "count": 1, "freeResize": true }
}
```

| 字段 | 说明 |
| --- | --- |
| `id` | **前端生成的节点 id**（`nodeKey`），不是数据库主键 |
| `type` | `image` / `text` / `config` / `video` / `audio` / `group`，插件节点为 `pluginId:name` |
| `metadata` | 结构随类型变化，后端**原样存取**，不做校验 |

`metadata` 为 null 时字段会被省略（全局 `non_null`），前端取值用 `node.metadata ?? {}`。

### 4. 连线结构（`CanvasConnectionVO`）

```json
{ "id": "e_0", "fromNodeId": "n_0", "toNodeId": "n_1" }
```

### 5. null 与空列表

| 传参 | 行为 |
| --- | --- |
| `nodes: null` 或省略 | **视为空列表 → 清空画布** |
| `viewport: null` 或省略 | 保持原值 |
| PATCH 里某字段省略 | 该字段不改 |

⚠️ `nodes` 的"省略等于清空"是刻意设计：快照保存的语义是"这份就是当前全部内容"。只想改视口请用 `PATCH`。

## 三、接口详解

### 1. 创建画布

```
POST /v1/projects
```

请求：

```json
{ "title": "我的第一张画布" }
```

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `title` | string | ✅ | 1–100 字符，首尾空格会被 trim |

响应 `200`：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": "2121533302950866945",
    "title": "阶段2验证画布",
    "nodeCount": 0,
    "connectionCount": 0,
    "backgroundMode": "lines",
    "createdAt": "2026-11-14T17:18:50",
    "updatedAt": "2026-11-14T17:18:50"
  },
  "traceId": "ef3e0346643e43299f6324d675ee5330"
}
```

错误：`400`（标题为空/超长）

### 2. 画布列表

```
GET /v1/projects?keyword=改过名&page=1&size=10
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `keyword` | ❌ | 标题模糊匹配，空串等于不传 |
| `page` | ❌ | 默认 1 |
| `size` | ❌ | 默认 20，最大 100 |

响应 `200`：

```json
{
  "code": 0,
  "data": {
    "page": 1,
    "pageSize": 10,
    "total": 1,
    "list": [
      {
        "id": "2121533469896749058",
        "title": "改过名的画布",
        "nodeCount": 1,
        "connectionCount": 0,
        "backgroundMode": "dots",
        "createdAt": "2026-11-14T17:19:30",
        "updatedAt": "2026-11-14T17:19:31"
      }
    ]
  },
  "traceId": "..."
}
```

**列表项不含 `nodes`**：一个画布几百个节点，20 条会把响应撑到几 MB。需要节点请调详情。

排序：`updatedAt DESC, id DESC`（同一秒内的多条靠 id 保证稳定）。

### 3. 画布详情

```
GET /v1/projects/{id}
```

响应 `200`：

```json
{
  "code": 0,
  "data": {
    "id": "2121533469896749058",
    "title": "改过名的画布",
    "version": 3,
    "backgroundMode": "dots",
    "showImageInfo": true,
    "viewport": { "x": 10.0, "y": 20.0, "k": 1.5 },
    "nodes": [
      {
        "id": "n_0",
        "type": "image",
        "title": "节点0",
        "position": { "x": 0.0, "y": 0.0 },
        "width": 320.0,
        "height": 240.0,
        "metadata": { "prompt": "提示词0", "model": "gpt-image-1" }
      }
    ],
    "connections": [
      { "id": "e_0", "fromNodeId": "n_0", "toNodeId": "n_1" }
    ],
    "createdAt": "2026-11-14T17:19:30",
    "updatedAt": "2026-11-14T17:19:31"
  },
  "traceId": "..."
}
```

节点顺序按数据库主键升序 = 插入顺序，**每次打开都一致**（渲染层级稳定）。

错误：`404` / `40410`（不存在或不属于当前用户）

### 4. 局部更新

```
PATCH /v1/projects/{id}
```

请求（只传要改的）：

```json
{
  "title": "重命名后的画布",
  "backgroundMode": "dots",
  "showImageInfo": true,
  "viewport": { "x": 10, "y": 20, "k": 1.5 }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `title` | string | ≤100，trim |
| `backgroundMode` | string | ≤32，**不做枚举校验**（主题值可能扩展） |
| `showImageInfo` | boolean | 是否显示图片信息 |
| `viewport` | object | `{x,y,k}` |

响应 `200`：返回更新后的 `ProjectSummaryVO`。

**注意**：此接口**不改变 `version`**。

错误：`400`（字段超长）、`404`（不存在）

### 5. 删除画布

```
DELETE /v1/projects/{id}
```

响应 `200`：

```json
{ "code": 0, "message": "success", "traceId": "..." }
```

逻辑删除：画布进回收站，**节点和连线保留**（恢复后内容完整）。

错误：`404` / `40410`

### 6. 复制画布

```
POST /v1/projects/{id}/copy
```

请求（可空）：

```json
{ "title": "我的画布 副本" }
```

不传 `title` 则自动用「原标题 副本」。

响应 `200`：新画布的 `ProjectSummaryVO`，`version=0`，节点和连线一起复制。

**节点 id 会复用**（同一 `nodeKey` 出现在两个项目里，因为唯一约束是项目内）。这对前端没有影响——两张画布各自独立打开。

### 7. 保存快照 ⭐

```
PUT /v1/projects/{id}/snapshot
```

请求：

```json
{
  "version": 3,
  "viewport": { "x": -120.5, "y": 33, "k": 0.85 },
  "nodes": [
    {
      "id": "n_0",
      "type": "image",
      "title": "节点0",
      "position": { "x": 0, "y": 0 },
      "width": 320,
      "height": 240,
      "metadata": { "prompt": "提示词0", "status": "success" }
    }
  ],
  "connections": [
    { "id": "e_0", "fromNodeId": "n_0", "toNodeId": "n_1" }
  ]
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `version` | ✅ | ≥0，必须是**上次读到的值** |
| `viewport` | ❌ | 不传保持原值 |
| `nodes` | ❌ | 最多 **500**，省略等于清空 |
| `connections` | ❌ | 最多 **2000**，省略等于清空 |

单节点约束：

| 字段 | 约束 |
| --- | --- |
| `id` | 非空，≤64，**同一次请求内唯一** |
| `type` | 非空，≤64 |
| `title` | ≤200 |
| `position.x` / `position.y` | 必填 |
| `width` / `height` | 必填，**≥0** |
| `metadata` | 任意 JSON |

单连线约束：`id` 非空且唯一；`fromNodeId` / `toNodeId` 必须**存在于本次 `nodes` 中**。

响应 `200`：

```json
{
  "code": 0,
  "data": {
    "id": "2121533302950866945",
    "version": 1,
    "nodeCount": 30,
    "connectionCount": 29,
    "updatedAt": "2026-11-14T17:18:05"
  },
  "traceId": "92c6af3cd9ae4ed9961a1e2fb91c88be"
}
```

**前端必须用响应里的 `version` 覆盖本地值**，否则下次保存必然 409。

错误：

| 状态 | code | 场景 |
| --- | --- | --- |
| 400 | 40000 | 节点 id 重复 / 连线引用不存在的节点 / 字段校验失败 |
| 404 | 40410 | 画布不存在或不属于当前用户 |
| 409 | 40920 | `version` 与服务器不一致（其他窗口先保存了） |

实测错误文案：

```
连线 e_x 的终点节点不存在：n_99
节点 id 重复：n_0
nodes[0].position: 节点缺少 position
nodes[0].width: 节点宽度不能为负
```

### 8. 导出画布

```
GET /v1/projects/{id}/export
```

响应 `200`：可直接存成 `.json` 文件。

```json
{
  "code": 0,
  "data": {
    "app": "infinite-canvas",
    "formatVersion": 1,
    "exportedAt": "2026-11-14T17:19:31",
    "title": "改过名的画布",
    "backgroundMode": "dots",
    "showImageInfo": true,
    "viewport": { "x": 10.0, "y": 20.0, "k": 1.5 },
    "nodes": [ ... ],
    "connections": [ ... ]
  },
  "traceId": "..."
}
```

**注意**：本阶段不含媒体文件，图片节点只有 `storageKey` 字符串（文件存储是阶段 3）。

### 9. 导入画布

```
POST /v1/projects/import
```

请求体 = 导出文件内容（`exportedAt` 会被忽略），`title` 可覆盖：

```json
{
  "app": "infinite-canvas",
  "formatVersion": 1,
  "title": "从文件导入的画布",
  "backgroundMode": "dots",
  "showImageInfo": true,
  "viewport": { "x": 10, "y": 20, "k": 1.5 },
  "nodes": [ ... ],
  "connections": [ ... ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `app` | ❌ | 建议带，便于排查 |
| `formatVersion` | ❌ | 大于 1 会被拒绝 |
| `title` | ❌ | 不传则为「导入的画布」 |

响应 `200`：**新画布的完整详情**（`ProjectDetailVO`），前端可直接跳进去。

错误：`400` / `导入文件版本过高（99），当前最多支持 1`

### 10. 回收站列表

```
GET /v1/projects/trash
```

响应 `200`：

```json
{
  "code": 0,
  "data": [
    {
      "id": "2121533469896749058",
      "title": "改过名的画布",
      "nodeCount": 1,
      "connectionCount": 0,
      "backgroundMode": "dots",
      "createdAt": "2026-11-14T17:19:30",
      "updatedAt": "2026-11-14T17:19:31"
    }
  ],
  "traceId": "..."
}
```

不分页（个人回收站数量有限，先简单实现）。

### 11. 恢复画布

```
POST /v1/projects/{id}/restore
```

响应 `200`：`{ "code": 0 }`

错误：`404` / `40410`（不存在、不是自己的、或已经恢复过）

## 四、错误码汇总

| code | HTTP | 含义 | 前端处理 |
| --- | --- | --- | --- |
| 0 | 200 | 成功 | — |
| 40000 | 400 | 参数/结构校验失败 | 提示 message |
| 40100 | 401 | 未登录 | 跳登录页 |
| 40113 | 401 | token 过期 | 静默刷新后重试 |
| 40114 | 401 | token 已撤销 | 跳登录页 |
| 40410 | 404 | 画布不存在/无权限 | 回列表页 |
| 40920 | 409 | **版本冲突** | 提示"已在其他窗口修改"，重新拉详情 |
| 50000 | 500 | 服务异常 | 提示稍后重试，附 traceId |

## 五、前端接入建议

### 1. 保存时机

画布是高频交互场景，**不要每次拖动节点都保存**。建议：

- 拖拽/缩放结束（`onPointerUp`）后 800ms 防抖；
- 页面 `beforeunload` 时强制保存一次（用 `navigator.sendBeacon` 或同步 XHR）；
- 手动保存按钮。

### 2. 409 的处理

```ts
if (res.code === 40920) {
  const latest = await getProject(id);        // 重新拉详情
  modal.confirm({
    title: "画布已在其他窗口修改",
    content: "重新加载会丢弃你刚才的修改，是否继续？",
    onOk: () => applySnapshot(latest.data),
  });
  return;
}
```

**不要自动重试**——重试等于把本地旧数据强行写进最新版本，会覆盖别人的修改。

### 3. 保存请求要"单飞"

多个保存请求并发会让版本号错乱（第二个必然 409）。建议用一个 `saving` 标志 + 最后一次请求覆盖前一次：

```ts
let savingPromise: Promise<void> | null = null;
let pending: Snapshot | null = null;

async function save(snapshot: Snapshot) {
  if (savingPromise) { pending = snapshot; return; }
  savingPromise = doSave(snapshot).finally(() => {
    savingPromise = null;
    if (pending) { const next = pending; pending = null; void save(next); }
  });
}
```

### 4. 列表页的节点数直接用返回值

`ProjectSummaryVO` 已经带了 `nodeCount` / `connectionCount`，**不要**为了显示"30 个节点"去拉详情。

### 5. 导入后直接跳转

`POST /projects/import` 返回的 `data.id` 就是新画布，前端可以直接 `navigate('/canvas/' + id)`，不需要再调一次详情。
