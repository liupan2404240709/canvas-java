# 阶段 3 设计文档：文件与素材库

> 记录"为什么这么做"，包括被否决的方案和它们的代价。功能清单看 [README](./README.md)，接口细节看 [api.md](./api.md)。

## 一、领域模型

```
                    ┌──────────────────────────────┐
                    │ t_file                       │
                    │ ├ id (PK, snowflake)         │
                    │ ├ user_id                    │  每条记录 = 一次"引用"
                    │ ├ sha256  ★ 内容标识         │
                    │ ├ storage_key  磁盘相对路径  │
                    │ ├ mime_type / extension      │  服务端判定
                    │ ├ bytes / width / height     │  上传时算好
                    │ ├ original_name              │
                    │ └ biz_type                   │  asset / canvas / avatar
                    └────────────┬─────────────────┘
                                 │ 0..1 : N
                                 │ file_id
                    ┌────────────▼─────────────────┐
                    │ t_asset                      │
                    │ ├ id (PK)                    │
                    │ ├ user_id                    │
                    │ ├ kind  text/image/video/audio│
                    │ ├ title / cover_url / source │
                    │ ├ note                       │
                    │ ├ content   ← 只有 text 有值  │
                    │ ├ file_id   ← 只有媒体素材有   │
                    │ ├ storage_key / bytes / mime │  冗余，列表页省 join
                    │ ├ width / height / duration  │
                    │ ├ tags_json (JSON 数组)      │
                    │ ├ metadata_json (JSON)       │
                    │ └ deleted / timestamps       │
                    └──────────────────────────────┘

磁盘：{app.storage.root}/ab/abcdef0123...     ← 无扩展名，内容是唯一标识
```

**聚合边界**：`t_asset` 是聚合根（业务对象）；`t_file` 不是聚合根，它是"某用户对某份字节的引用"。素材引用了文件，但素材删除**不级联删文件**——文件的生命周期由"还有没有记录指向它"决定。

## 二、核心流程

### 2.1 上传（multipart 与 base64 共用的主线）

```
POST /v1/files/upload  (file, bizType)
        │
        ▼
① normalize(Content-Type, 文件名)  → mime
        │  客户端没给 Content-Type 时按扩展名猜；image/jpg → image/jpeg
        ▼
② categoryOf(mime) 在白名单里？  ── 否 → 400 / 40020
        │
        ▼
③ 建临时文件 {root}/.tmp/upload-xxx.part，边读边写：
     累加 total（> limit 立刻 400 / 40021）、digest.update()、out.write()
        │
        ▼
④ sha256 = hex(digest)
        │
        ▼
⑤ 魔数校验（仅图片）── 不符 → 400 / 40020（临时文件在 finally 删掉）
        │
        ▼
⑥ 查 t_file(user_id, sha256) 命中？ ── 是 → 删临时文件，返回已有记录 deduped=true
        │  否
        ▼
⑦ 图片读宽高（ImageReader，只读文件头）
        │
        ▼
⑧ 原子 move 到 {root}/{sha[0:2]}/{sha256}   ← 目标已存在则直接丢弃临时文件
        │
        ▼
⑨ INSERT t_file（唯一索引冲突 = 并发上传同一份内容 → 退回⑥的语义）
        │
        ▼
返回 FileVO{ id, url, storageKey, sha256, bytes, width, height, deduped }
```

### 2.2 建素材

```
POST /v1/assets
        │
        ├─ kind=text  → 必须有 content，且不能带 fileId
        │
        └─ kind=image/video/audio
                 │
                 ▼
         requireFile(userId, fileId)        ← 归属校验，失败 404 / 40430
                 │
                 ▼
         categoryOf(file.mimeType) == kind？ ── 否 → 400（类型不匹配）
                 │
                 ▼
         从文件记录带出 bytes / mimeType / width / height / storageKey
         （**不接受客户端传这些字段**）
                 │
                 ▼
         coverUrl 为空且是图片 → 用文件地址填上
                 │
                 ▼
         INSERT t_asset
```

### 2.3 文件内容读取（Range）

```
GET /v1/files/{id}/content   [Range: bytes=0-1023]
        │
        ├─ 无 Range → 200 + 整个 Resource + Content-Length + Accept-Ranges: bytes
        │
        └─ 有 Range → HttpRange.parseRanges
                        │
                        ├─ 解析失败 → 当作无 Range（200 整份），比 416 宽容
                        └─ 取第一个区间 → toResourceRegion → 206 + Content-Range
```

### 2.4 删除与清理

```
DELETE /v1/files/{id}
        ├─ 被 t_asset 引用？ → 409 / 40930（不级联，不静默）
        └─ 删记录 → 全库还有别的记录指向同一 sha256？
                        ├─ 有 → 只删记录，磁盘留着
                        └─ 没有 → 删磁盘文件

POST /v1/files/cleanup?graceMinutes=1440
        └─ SELECT t_file WHERE user_id=? AND created_at < now-宽限期
                          AND NOT EXISTS (SELECT 1 FROM t_asset WHERE file_id = t_file.id)
           → 删记录 → 按上面同一套引用计数决定是否删磁盘
```

## 三、关键决策

| # | 决策 | 选了 | 被否决的方案 | 原因 |
| --- | --- | --- | --- | --- |
| 1 | 文件存哪 | 磁盘 + `storage_key` | 直接存 MySQL BLOB | BLOB 让备份/迁移/CDN 全部变难；InnoDB 行外存还会放大 redo log |
| 2 | 文件名 | 内容寻址 `{sha[0:2]}/{sha256}` | UUID / 日期目录 | UUID 无法去重；日期目录让同一份内容跨天落两份；内容寻址天然幂等且可校验完整性 |
| 3 | 扩展名 | 不进路径，存 `mime_type` | 拼进文件名 | 同一份内容被声明成 png / jpeg 会落两份盘，秒传失效 |
| 4 | url 是否落库 | 不落库，服务端拼 | 存 `url` 列 | 换域名、挂 CDN、迁对象存储都要全表刷数据；`storageKey` 才是稳定标识 |
| 5 | 秒传粒度 | `(user_id, sha256)` 唯一 | 全库 `sha256` 唯一 | 全库唯一意味着"别人传过我就不能删"，额度和删除语义会纠缠不清 |
| 6 | 磁盘共享 | 跨用户共享同一份字节 | 每个用户各存一份 | 节省空间，代价是删除要引用计数（已实现，见 tech/11） |
| 7 | 删除语义 | `t_file` 物理删除；`t_asset` 逻辑删除 | 两张表都逻辑删除 | 文件无回收站语义；素材有，可恢复 |
| 8 | 校验 MIME | 白名单 + 图片魔数 | 只信 `Content-Type` | 客户端声明的 MIME 只是一个字符串，改个扩展名就能绕过 |
| 9 | 视频/音频魔数 | 不校验 | 手写 ftyp/EBML 判断 | 容器格式变体太多，手写必漏；要么上 Tika，要么不假装能校验 |
| 10 | 图片宽高 | `ImageReader` 读文件头 | `ImageIO.read` + `getWidth` | 整图解码，8000×8000 一张就 256MB，并发几个直接 OOM |
| 11 | 访问方式 | 公开 `/media/**` + 鉴权 `/content` | 只做鉴权下载 | `<img>`/`<video>` 不会带 Authorization 头，只做鉴权等于前端没法显示 |
| 12 | Range 实现 | `BoundedResource` 裁剪 + 统一返回 `Resource` | `ResourceRegion` + `ResponseEntity<?>` | 实测后者会 500：`<?>` 让目标类型退化成 `Object`，`ResourceRegionHttpMessageConverter` 判定自己写不了（详见 tech/08） |
| 13 | 标签存储 | JSON 数组 + `JSON_CONTAINS` | 关联表 `t_asset_tag` | 标签弱结构、改动频繁；这个小数据量下关联表的 diff 同步成本 > 查询收益 |
| 14 | 标签聚合 | `JSON_TABLE` + group by | 全查出来在 Java 里统计 | 少一次全量传输，SQL 也更直观 |
| 15 | 素材删了文件怎么办 | 不删，靠手动 cleanup 收孤儿 | 删素材时立即删文件 | 素材可逻辑恢复，立即删文件会让恢复出来裂图 |
| 16 | 清理触发 | 手动 + 宽限期 + 单批 500 | `@Scheduled` 自动清 | 误删不可逆，先把触发权交给用户，阶段 8 再加定时任务 |
| 17 | 存储抽象 | `FileStorage` 接口 | 直接到处 `Paths.get` | 本地 / MinIO / S3 的能力差异（原子 move vs PUT、静态映射 vs 预签名）必须关在实现里 |
| 18 | 媒体 MIME 来源 | `MediaController` 查 `t_file.mime_type` + 内存缓存 | `addResourceHandlers` 静态映射 | 静态映射只按扩展名推断，而存储路径不带扩展名 → 一律 `octet-stream` → 配 `nosniff` 后图片直接不显示 |
| 19 | 清理宽限语义 | `graceMinutes=0` 表示"不设宽限" | `0` 回落到默认 1440 | 契约写的是"传 0 强制清理"，实现却把 0 当"没传"，属于参数语义与文档相反，实测时表现为"孤儿怎么都清不掉" |
| 20 | 可视化控制台 | 放 `resources/static/demo`，与接口同源 | 给前端加 API 层先跑起来 | 前端从设计上就没有后端数据通道（IndexedDB + 直连模型），改造属于阶段 9；控制台是零依赖单页，`mvn spring-boot:run` 就能看到真实效果 |

## 四、一致性：数据库与磁盘

这是本阶段唯一无法用事务解决的问题。三种做法：

| 做法 | 说明 | 结果 |
| --- | --- | --- |
| 先写文件，后写库 | 推荐 | 文件写成功但 insert 失败 → 磁盘多一份"没人知道"的字节。**能靠 cleanup 收掉**（孤儿文件就是这么定义的） |
| 先写库，后写文件 | | 库里有一条指向不存在文件的记录 → 用户看到裂图，且无法自动修复 |
| 两阶段（写临时 → 提交 → 改状态） | 复杂 | 需要状态机 + 补偿任务，收益不成比例 |

选第一种，并且**把 cleanup 的定义设计成正好覆盖这个失败模式**：`t_file` 里不存在 = 孤儿。这样"写文件成功、写库失败"不是数据错误，只是暂时的垃圾。

反向的"库里有、磁盘没有"（`content` 接口发现文件不存在 → 404 而不是 500）也做了兜底。

## 五、被否决的方案

**"上传时顺手生成缩略图"**：看起来是标配，但这个项目里列表页用的是 CSS `object-fit` 裁切，原图在一个 320px 的卡片里并不会明显变慢；而缩略图要新增列、新增清理逻辑、新增"缩略图生成失败"的降级分支。先用最少的代码跑通，等真的性能不够再加——加的时候只要在 `FileStorage` 后面插一步，不影响已有接口。

**"用 Tika 做类型检测"**：能真正判断文件类型，但要多一个 10MB+ 的依赖和一份 magic 规则库。当前只需要挡住"把可执行文件改成 .png"这一类，图片魔数已经够。**知道自己什么时候不该引入大件，也是设计能力。**

**"素材表不分 text/media，统一用 content 列"**：试过，`content` 列要存几千字文本，又要对图片为空，语义会漂；类型不匹配的错误也没法在建素材时就拦住。分成"文本走 content、媒体走 file_id"两条路径，反而每条都更短。

## 六、安全边界

| 风险 | 处理 |
| --- | --- |
| 任意文件上传 | MIME 白名单（11 种） |
| 伪装扩展名 | 图片魔数校验 |
| 路径穿越 | `resolve()` 里 `normalize()` + `startsWith(root)` |
| 越权读文件 | `/v1/files/{id}/**` 全部校验 `user_id`，越权 404 |
| 公开访问泄露 | 路径是 sha256，不可枚举；生产应换私有桶 + 预签名 |
| 超大文件打满磁盘 | 每类上限 + 容器上限，两层拦截 |
| 上传接口被刷 | 阶段 8 加限流（Redis 令牌桶） |
| 目录文件过多 | sha256 前两位分片，最多 256 个子目录 |

## 七、接口一览

完整契约见 [api.md](./api.md)。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/v1/files/upload` | multipart 上传 |
| POST | `/v1/files/base64` | dataURL 上传 |
| GET | `/v1/files/{id}` | 文件信息 |
| GET | `/v1/files/{id}/content` | 文件内容（支持 Range） |
| DELETE | `/v1/files/{id}` | 删文件（被引用时 409） |
| POST | `/v1/files/cleanup` | 清理孤儿文件 |
| POST | `/v1/assets` | 建素材 |
| GET | `/v1/assets` | 列表（kind/keyword/tag/分页） |
| GET | `/v1/assets/tags` | 标签聚合 |
| GET | `/v1/assets/{id}` | 详情 |
| PATCH | `/v1/assets/{id}` | 局部更新 |
| DELETE | `/v1/assets/{id}` | 逻辑删除 |
| GET | `/media/{shard}/{sha256}` | 公开媒体访问（免 token，支持 Range） |
| GET | `/demo` | 可视化控制台（免 token） |

## 八、遗留问题

1. **单实例假设**：cleanup 两个请求并发时，可能重复删同一个磁盘文件（`deleteIfExists` 兜住了，不会报错，但计数会不准）。多实例要加 Redis 分布式锁。
2. **没有配额**：用户可以无限上传。等有 `t_user_quota` 再说。
3. **`t_asset` 与画布节点没有关联**：画布节点里存的是前端给的 url/storageKey，后端追不到。这直接影响 cleanup 的安全性——所以 cleanup 只处理"从未被任何素材引用"的文件，宽限期默认 24 小时。
4. **WebP 没有宽高**：JDK 自带 ImageIO 不支持，需要时引 TwelveMonkeys 或改成前端传。
