# 阶段 3 接口文档：文件与素材库

> 所有接口都在 `/api` 之下（context-path），下文路径省略该前缀。
> 时间格式 `yyyy-MM-dd'T'HH:mm:ss`；所有 id 出参都是**字符串**（避免前端 Number 精度丢失）。

## 一、接口总览

| # | 方法 | 路径 | 说明 | 需登录 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/v1/files/upload` | multipart 上传 | ✅ |
| 2 | POST | `/v1/files/base64` | dataURL / base64 上传 | ✅ |
| 3 | GET | `/v1/files/{id}` | 文件信息 | ✅ |
| 4 | GET | `/v1/files/{id}/content` | 文件内容（支持 Range） | ✅ |
| 5 | DELETE | `/v1/files/{id}` | 删除文件 | ✅ |
| 6 | POST | `/v1/files/cleanup` | 清理孤儿文件 | ✅ |
| 7 | POST | `/v1/assets` | 新建素材 | ✅ |
| 8 | GET | `/v1/assets` | 素材列表 | ✅ |
| 9 | GET | `/v1/assets/tags` | 标签聚合 | ✅ |
| 10 | GET | `/v1/assets/{id}` | 素材详情 | ✅ |
| 11 | PATCH | `/v1/assets/{id}` | 局部更新 | ✅ |
| 12 | DELETE | `/v1/assets/{id}` | 删除素材（逻辑删除） | ✅ |
| 13 | GET | `/api/media/{shard}/{sha256}` | 公开媒体访问（**免登录**，支持 Range） | ❌ |
| 14 | GET | `/api/demo` | 可视化控制台页面（**免登录**，重定向到 index.html） | ❌ |

**未登录访问 1~12 一律 `401` + `40100`。**

## 二、通用约定

### 1. 文件对象（`FileVO`）

```json
{
  "id": "1857392847293847296",
  "url": "/api/media/9f/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "storageKey": "9f/9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "name": "red.png",
  "mimeType": "image/png",
  "extension": "png",
  "bytes": 1234,
  "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
  "width": 320,
  "height": 240,
  "deduped": false,
  "createdAt": "2026-11-16T10:12:33"
}
```

| 字段 | 说明 |
| --- | --- |
| `url` | 可直接给 `<img src>` 用，**不需要 token**。含 context-path |
| `storageKey` | 磁盘相对路径，导出/迁移时用它定位 |
| `width` / `height` | 只有图片有；WebP 为空（JDK ImageIO 不支持） |
| `deduped` | 仅上传接口有意义：`true` 表示命中秒传、没有真正落盘 |

### 2. 素材对象（`AssetVO`）

第一层字段对齐前端 `web/src/stores/use-asset-store.ts` 的 `AssetBase`，第二层 `data` 按 `kind` 取用其中一部分（未使用的字段不会出现，全局 `non_null`）。

**文本素材**

```json
{
  "id": "1857392847293847297",
  "kind": "text",
  "title": "赛博朋克提示词",
  "coverUrl": null,
  "tags": ["赛博朋克", "夜景"],
  "source": "手动",
  "note": null,
  "createdAt": "2026-11-16T10:12:35",
  "updatedAt": "2026-11-16T10:12:35",
  "data": { "content": "霓虹雨夜，赛博朋克街道，45mm" }
}
```

**图片素材**（`data` 只有尺寸那几项）

```json
{
  "id": "1857392847293847298",
  "kind": "image",
  "title": "红色方块",
  "coverUrl": "/api/media/9f/9f86d0...",
  "tags": ["纯色", "测试"],
  "source": "手动",
  "note": "e2e",
  "createdAt": "2026-11-16T10:12:36",
  "updatedAt": "2026-11-16T10:12:36",
  "data": {
    "dataUrl": "/api/media/9f/9f86d0...",
    "storageKey": "9f/9f86d0...",
    "width": 320,
    "height": 240,
    "bytes": 1234,
    "mimeType": "image/png"
  }
}
```

> `data.dataUrl` 这个名字是为了对齐前端 `ImageAsset.dataUrl`，服务端放的是公开地址，不是 base64 串。

### 3. 支持的文件类型与上限

| 类别 | MIME | 上限 |
| --- | --- | --- |
| image | `image/jpeg`、`image/png`、`image/webp`、`image/gif` | 20MB |
| video | `video/mp4`、`video/webm`、`video/quicktime` | 100MB |
| audio | `audio/mpeg`、`audio/wav`、`audio/ogg`、`audio/mp4` | 20MB |

容器层上限（`spring.servlet.multipart`）：`max-file-size=128MB`、`max-request-size=160MB`。
**容器先拦一次，应用层再按类型拦一次**，后者给的提示更具体。

## 三、接口详情

### 1. POST `/v1/files/upload`

`Content-Type: multipart/form-data`

| 参数 | 位置 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | form-data | ✅ | 文件本体 |
| `bizType` | query | ❌ | `asset`（默认）/ `canvas` / `avatar`，仅做标记 |

```bash
curl -X POST http://127.0.0.1:8081/api/v1/files/upload \
  -H "Authorization: Bearer $T" \
  -F "file=@red.png" \
  -F "bizType=asset"
```

成功：`code=0`，`data` 为 `FileVO`。

| 失败 | code | 说明 |
| --- | --- | --- |
| `400` | `40020` | MIME 不在白名单，或图片魔数与声明不符 |
| `400` | `40021` | 超过该类型上限 |
| `400` | `40022` | 空文件 |
| `500` | `50000` | 磁盘写入失败 |

### 2. POST `/v1/files/base64`

```json
{
  "dataUrl": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg...",
  "filename": "paste.png",
  "bizType": "asset"
}
```

- `dataUrl` 支持完整 dataURL，也支持**裸 base64**（没有 `data:` 前缀时按 `filename` 猜类型）。
- 解码用 MIME 解码器，换行/空格会被忽略。
- 同一个文件既走 multipart 又走 base64，两次返回**同一个 `fileId`**（内容相同就命中秒传）。
- `filename` 不传时默认 `base64.png` 之类。

失败：`400` / `40000`（dataURL 格式错、base64 解不出来）、`40020`、`40021`、`40022`。

### 3. GET `/v1/files/{id}`

返回 `FileVO`。不存在或不属于当前用户：`404` / `40430`。

### 4. GET `/v1/files/{id}/content`

| 参数 | 位置 | 说明 |
| --- | --- | --- |
| `Range` | header | 如 `bytes=0-1023`、`bytes=1024-`；不传返回整份 |
| `download` | query | `true` 时 `Content-Disposition: attachment`，否则 `inline` |

响应头：

| 头 | 值 |
| --- | --- |
| `Content-Type` | 库里存的 `mime_type` |
| `Content-Length` | 整份时为总字节数 |
| `Accept-Ranges` | `bytes` |
| `Content-Range` | 仅 206 时：`bytes 0-9/1234` |
| `Content-Disposition` | `inline; filename*=UTF-8''red.png` |
| `Cache-Control` | `private, max-age=86400`（内容寻址，字节永不改变） |

状态码：`200`（整份）、`206`（区间）、`404`（记录不存在 / 磁盘文件丢失）。

```bash
# 取前 10 个字节
curl -i http://127.0.0.1:8081/api/v1/files/1857.../content \
  -H "Authorization: Bearer $T" -H "Range: bytes=0-9"
```

> 多个区间（`bytes=0-9,20-29`）只返回第一个区间，不做 `multipart/byteranges`。

### 5. DELETE `/v1/files/{id}`

| 情况 | 结果 |
| --- | --- |
| 被素材引用 | `409` / `40930`，message 带引用数 |
| 未被引用，但别的用户也传过同一份内容 | 只删记录，磁盘文件保留 |
| 最后一条记录 | 记录 + 磁盘文件一起删 |

### 6. POST `/v1/files/cleanup`

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `graceMinutes` | 1440 | 只处理创建时间早于 `now - graceMinutes` 的文件；`<=0` 时按 1440 处理 |

```json
{ "scanned": 2, "removedFiles": 1, "freedBytes": 4096, "hasMore": false }
```

- `scanned`：扫到的孤儿数（单次最多 500）
- `removedFiles`：真正删掉的**磁盘文件**数（同一份内容被别的账号引用时不删磁盘，只删记录）
- `hasMore`：`true` 表示还取满了 500 条，可以再调一次

**只清理"从未被任何素材引用过"的文件**，已删除的素材也算"引用过"（`t_asset.deleted` 不参与判断），所以删素材不会导致磁盘文件被清走。

### 7. POST `/v1/assets`

```json
{
  "kind": "image",
  "title": "红色方块",
  "coverUrl": null,
  "source": "手动",
  "note": "e2e",
  "fileId": "1857392847293847296",
  "tags": ["纯色", "测试"],
  "durationMs": null,
  "metadata": { "from": "canvas" }
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `kind` | ✅ | `text` / `image` / `video` / `audio` |
| `title` | ✅ | ≤200 字符 |
| `content` | `kind=text` 必填 | 文本正文；非 text 传了会被 400 拒绝 |
| `fileId` | 媒体素材必填 | 文本素材传了会被 400 拒绝 |
| `coverUrl` | ❌ | 图片素材不传时后端自动填文件地址 |
| `tags` | ❌ | 数组，单标签 ≤32 字符，自动 trim + 去重 |
| `durationMs` | ❌ | 音视频时长，前端算好传，后端不解析容器 |
| `metadata` | ❌ | 任意 JSON，原样存原样回 |

**宽高/字节数/MIME 不接受客户端传**，一律从 `t_file` 带出来。

失败：`400`/`40000`（缺字段、类型不匹配、text 带 fileId 等）、`404`/`40430`（fileId 不存在或不是自己的）。

### 8. GET `/v1/assets`

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `kind` | 不限 | `text`/`image`/`video`/`audio`；`all` 或留空表示不限 |
| `keyword` | — | 同时匹配 `title`、`source`、`note`、`content`（`LIKE %x%`） |
| `tag` | — | 精确匹配某个标签（`JSON_CONTAINS`） |
| `page` | 1 | `<1` 按 1 |
| `size` | 20 | `>100` 按 100，`<=0` 按 20（静默压边界） |

排序：`created_at DESC, id DESC`。

```json
{ "code": 0, "data": { "page": 1, "pageSize": 10, "total": 2, "list": [ ... ] } }
```

### 9. GET `/v1/assets/tags`

```json
{ "code": 0, "data": [ { "tag": "测试", "count": 2 }, { "tag": "纯色", "count": 1 } ] }
```

按 `count` 倒序，最多 200 个。

### 10. GET `/v1/assets/{id}`

返回 `AssetVO`。不存在或不属于当前用户：`404` / `40431`。

### 11. PATCH `/v1/assets/{id}`

只更新传了的字段。语义细节：

| 想做的事 | 传什么 |
| --- | --- |
| 改标题 | `{"title": "新标题"}` |
| 清空备注 | `{"note": ""}` |
| 清空标签 | `{"tags": []}` |
| 什么都不改 | `{}`（直接返回当前值，不写库） |
| 改类型 | `400`（不允许） |
| 给图片素材改正文 | `400` |

### 12. DELETE `/v1/assets/{id}`

逻辑删除，返回 `{"code":0,"message":"success"}`。重复删除：`404` / `40431`。
**磁盘文件不会被删**，需要回收空间请调 `/v1/files/cleanup`（且只有"从未被引用"的文件会被清）。

### 13. GET `/api/media/{shard}/{sha256}`

公开只读，由 `MediaController` 提供，**不需要 token**，给 `<img>` / `<video>` 直接用。

- `shard` 是 sha256 的前两位（如 `9f`），`sha256` 是 64 位小写 hex；
- 路径不可枚举（sha256 是内容的函数），所以公开访问是可接受的；
- 响应头带**真实 MIME**（从 `t_file.mime_type` 取），不是 `application/octet-stream`；
- 支持 `Range: bytes=0-1023`，返回 `206` + `Content-Range`；
- 区间完全越界返回 `416`；
- `Cache-Control: public, max-age=86400`（内容寻址 = 字节永不改变）。

```bash
# 直连
curl -I http://127.0.0.1:8081/api/media/9f/9f86d081884c7d65...
# HTTP/1.1 200
# Content-Type: image/png
# Content-Length: 779
# Cache-Control: public, max-age=86400
# Accept-Ranges: bytes

# 取前 10 个字节
curl -i -H "Range: bytes=0-9" http://127.0.0.1:8081/api/media/9f/9f86d081...
# HTTP/1.1 206
# Content-Range: bytes 0-9/779
```

路径格式不合法、`t_file` 里没有这条记录、或磁盘文件已被清理，都返回 `404` + `40430`。

> 这条路径曾经由 `WebMvcConfig` 的 `addResourceHandlers` 提供（零开销、原生 Range），但它推断不出 MIME——文件名是 sha256 没有扩展名，一律返回 `octet-stream`，配上 `nosniff` 后浏览器直接拒绝渲染图片。改成 Controller 的原因和踩坑细节见 [tech/08](./tech/08-媒体访问与Range下载.md)。

生产环境更稳的做法：对象存储私有桶 + 预签名 URL（有效期几分钟，泄露后很快失效）。

## 四、完整调用示例

```bash
T="<accessToken>"
B="http://127.0.0.1:8081/api/v1"

# 1. 上传
FILE_ID=$(curl -s -X POST $B/files/upload -H "Authorization: Bearer $T" \
  -F "file=@red.png" | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")

# 2. 建素材
ASSET_ID=$(curl -s -X POST $B/assets -H "Authorization: Bearer $T" \
  -H "Content-Type: application/json" \
  -d "{\"kind\":\"image\",\"title\":\"红色方块\",\"fileId\":\"$FILE_ID\",\"tags\":[\"纯色\"]}" \
  | python -c "import sys,json;print(json.load(sys.stdin)['data']['id'])")

# 3. 列表 + 标签
curl -s "$B/assets?kind=image&tag=纯色" -H "Authorization: Bearer $T"
curl -s "$B/assets/tags" -H "Authorization: Bearer $T"

# 4. 清理
curl -s -X DELETE "$B/assets/$ASSET_ID" -H "Authorization: Bearer $T"
curl -s -X POST "$B/files/cleanup?graceMinutes=0" -H "Authorization: Bearer $T"
```
