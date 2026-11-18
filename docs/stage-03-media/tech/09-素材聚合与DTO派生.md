# 09 · 素材聚合：DTO 派生与"不信客户端"

## 一、一句话定位

`AssetServiceImpl#create` 做三件事：**校验类型组合、从文件记录派生元信息、把标签/扩展字段序列化**。核心原则是——**能推导的字段一律不接受客户端传值**。

## 二、核心代码

```java
if (KIND_TEXT.equals(kind)) {
    if (!StringUtils.hasText(request.content())) {
        throw new BizException(ResultCode.BAD_REQUEST, "文本素材必须提供 content");
    }
    if (request.fileId() != null) {
        throw new BizException(ResultCode.BAD_REQUEST, "文本素材不能关联文件");
    }
    asset.setContent(request.content());
} else {
    if (request.fileId() == null) {
        throw new BizException(ResultCode.BAD_REQUEST, "媒体素材必须提供 fileId");
    }
    FileVO file = fileService.detail(userId, request.fileId());

    String fileCategory = MediaTypes.categoryOf(file.mimeType());
    if (!kind.equals(fileCategory)) {
        throw new BizException(ResultCode.BAD_REQUEST,
                "素材类型与文件类型不匹配：文件是 " + (fileCategory == null ? "未知" : fileCategory));
    }

    asset.setFileId(file.id());
    asset.setStorageKey(file.storageKey());
    asset.setBytes(file.bytes());
    asset.setMimeType(file.mimeType());
    asset.setWidth(file.width());
    asset.setHeight(file.height());

    if (!StringUtils.hasText(asset.getCoverUrl()) && MediaTypes.isImage(file.mimeType())) {
        asset.setCoverUrl(file.url());
    }
}
```

## 三、为什么宽高/字节数/MIME 不让客户端传

请求体里**没有** `width` / `height` / `bytes` / `mimeType` 这四个字段。这不是遗漏，是设计。

| 让客户端传的问题 | 后果 |
| --- | --- |
| 卡片布局错位 | 前端按错误的宽高算 aspect-ratio，图片被拉伸或裁掉 |
| 无法排查 | "为什么这张图显示 0×0"——要同时怀疑前端、后端、浏览器 |
| 可被伪造 | 传个 `width: 99999` 撑爆布局，或者谎报 `mimeType` 绕过前端判断 |
| 多端不一致 | Web 端和未来的移动端各算一套，规则漂移 |

**文件已经上传过了，服务端手上就有真实数据**（`t_file` 里的 `bytes/width/height/mime_type`），没有任何理由再问一遍调用方。

这条原则在面试里可以推广成一句话：**服务端能自己算出来的，就不要让客户端告诉你。**

## 四、`kind` 与文件类型必须匹配

```java
String fileCategory = MediaTypes.categoryOf(file.mimeType());   // "image" / "video" / "audio" / null
if (!kind.equals(fileCategory)) { ... }
```

不做这一步会怎样：用户把一段 mp3 挂成 `kind=image` 的素材。

```
t_asset.kind = image
t_asset.mime_type = audio/mpeg
t_asset.data.dataUrl = /api/media/xx/mp3sha
```

前端渲染卡片时：

```tsx
<img src={cover} />        // 浏览器拿到 audio/mpeg，不显示，只留一个破图
```

而且这个错误**在列表页极难定位**——看起来只是"某张图加载失败"。

类型校验放在**写入时**（fail fast），而不是读取时给每个 VO 加 `if` 分支。

## 五、文本与媒体是两条路径，不是同一条带 if

```
                     ┌── kind=text  →  content 必填，fileId 必须为空
POST /v1/assets  ────┤
                     └── kind=media →  fileId 必填，content 必须为空
```

看起来可以合并成"两个字段至少有一个"，但有三个具体收益：

1. **错误信息精确**："文本素材必须提供 content" vs "参数不合法"；
2. **数据库列语义干净**：`content` 只对 text 有值，查询时不会出现"图片素材的 content 是空串还是 NULL"这种歧义；
3. **更新时能拦住**：`PATCH` 里给图片素材改 `content` 直接 400（详见第六节）。

## 六、PATCH 语义：只改传了的字段

```java
LambdaUpdateWrapper<Asset> wrapper = new LambdaUpdateWrapper<Asset>()
        .eq(Asset::getId, assetId)
        .eq(Asset::getUserId, userId);
boolean touched = false;

if (request.title() != null) { wrapper.set(Asset::getTitle, trimTitle(request.title())); touched = true; }
if (request.tags() != null)  { wrapper.set(Asset::getTagsJson, writeTags(request.tags())); touched = true; }
...
if (!touched) {
    return toVO(asset);              // 空对象直接返回，不写库
}
assetMapper.update(null, wrapper);
```

为什么不 `updateById(entity)`：`updateById` 会把**实体里所有非 null 字段**都写进去。如果先 `selectById` 拿到旧实体再改一个字段，看似没问题；但如果哪天有人图省事 `new Asset()` 只 set 了 title，其余字段就会被写成 null —— **一次"改标题"把标签清空了**。

`LambdaUpdateWrapper.set()` 只出现在 `SET` 子句里显式声明过的列，天然是 PATCH 语义。

### `updated_at` 怎么刷新

`update(null, wrapper)` 拿不到实体，`AuditMetaObjectHandler.updateFill` 不会执行。但 `t_asset.updated_at` 有：

```sql
updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
```

只要 `SET` 子句里**没有显式给 `updated_at` 赋值**，MySQL 就会自动刷新它。

反过来说，阶段 2 踩过的那个坑（`strictUpdateFill` 把旧时间显式写回 SQL，导致 `ON UPDATE` 不触发）在这里天然不存在。**同一个问题的两种规避方式：要么不写这一列，要么用 `setFieldValByName` 强制覆盖。**

因为 `update(null, wrapper)` 之后内存里的 `asset` 还是旧值，所以重新查一次：

```java
return toVO(requireAsset(userId, assetId));
```

多一次主键查询，换"出参一定和库里一致"。

## 七、标签的序列化：`writeTags` / `readTags`

```java
private String writeTags(List<String> tags) {
    if (tags == null || tags.isEmpty()) {
        return null;                       // 空数组存 NULL，不存 "[]"
    }
    List<String> cleaned = tags.stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .distinct()
            .toList();
    return cleaned.isEmpty() ? null : writeJson(objectMapper.valueToTree(cleaned));
}
```

三个处理：

| 处理 | 原因 |
| --- | --- |
| `filter(hasText)` | 前端 `Select mode="tags"` 可能产出空串标签 |
| `.map(trim)` | 用户手输" 人像 "和"人像"应该是同一个 |
| `.distinct()` | 同一个标签不该出现两次，否则 `JSON_CONTAINS` 统计会重复 |

空数组存 `NULL` 而不是 `"[]"`：两者在查询上等价（都不匹配任何 `JSON_CONTAINS`），但 `NULL` 能让 `selectTagCounts` 里的 `tags_json IS NOT NULL` 过滤掉更多行。**统一用一种表示**，避免"有人的是 `[]`、有人的是 `null`"。

`readTags` 反过来：

```java
private List<String> readTags(String tagsJson) {
    if (!StringUtils.hasText(tagsJson)) {
        return List.of();
    }
    try {
        return objectMapper.readValue(tagsJson, objectMapper.getTypeFactory()
                .constructCollectionType(List.class, String.class));
    } catch (JsonProcessingException e) {
        return List.of();                  // 脏数据不该让整个列表 500
    }
}
```

注意这里的容错策略和 `metadata` 不同：`metadata` 解析失败抛 `IllegalStateException`（走 500），`tags` 解析失败返回空数组。

区别在于**影响面**：`tags` 坏了只是少几个标签，列表还能看；`metadata` 是用户数据结构，静默吞掉会让用户以为数据丢了，**宁可报错也不要假装正常**。

## 八、`AssetDataVO` 的"扁平联合类型"

前端是个联合类型：

```ts
type TextAsset  = { kind: "text";  data: { content: string } }
type ImageAsset = { kind: "image"; data: { dataUrl; storageKey?; width; height; bytes; mimeType } }
type VideoAsset = { kind: "video"; data: { url; storageKey?; width; height; bytes; mimeType } }
```

Java 侧用一个"字段全都有、用不上的为 null"的 record：

```java
public record AssetDataVO(String content, String dataUrl, String storageKey,
                          Integer width, Integer height, Long bytes,
                          String mimeType, Integer durationMs) { }
```

配合全局 `spring.jackson.default-property-inclusion: non_null`，实际输出：

```json
// 文本
{ "content": "霓虹雨夜..." }
// 图片
{ "dataUrl": "/api/media/...", "width": 320, "height": 240, "bytes": 1234, "mimeType": "image/png" }
```

为什么不拆成 `TextDataVO` / `ImageDataVO`：那需要在 Service 里做 `instanceof` 分支，或者让 `AssetVO` 变成泛型，最后所有调用方都要处理类型。**用"多态"换来的灵活，在这个场景里等于把复杂度转嫁给每个读代码的人。**

## 九、面试考察点

**Q1：为什么入参和出参不复用一个类？**

`CreateAssetRequest` 里没有 `bytes/width/height/mimeType/fileId` 之外的服务端字段，`AssetVO` 里没有 `userId`、没有 `deleted`。复用会带来两类风险：
- 入参多了不该传的字段（`userId`！能被伪造就越权）；
- 出参泄露内部字段（`deleted`、加密后的 key）。

阶段 2 的 [08 DTO/VO 分层](../../stage-02-canvas/tech/08-DTO与VO分层.md) 有更完整的论证。

**Q2：`@Valid` 能校验"fileId 和 content 二选一"吗？**

单字段约束（`@NotBlank`、`@Size`）能，跨字段条件约束不能。
可以用 `@AssertTrue` 写一个方法：

```java
@AssertTrue(message = "文本素材必须提供 content")
public boolean isContentValid() { ... }
```

但错误信息会挂在整个对象上（字段名是 `contentValid`），前端定位不到具体字段。本项目选**在 Service 里手写校验**——条件分支本来就三种（text / 媒体 / 都不对），用注解反而绕。

**Q3：为什么改写素材不让改 `kind`？**

改了 kind 意味着：`content` 该清空、`file_id` 该指向新文件、宽高该重算——本质上是**另一条数据**。与其实现一套"类型迁移"逻辑，不如让用户删了重建。这也是为什么 `UpdateAssetRequest` 里保留了 `kind` 字段但不允许改动（前端回传整个对象时不至于 400）。

## 十、常见坑

| 坑 | 现象 | 原因 |
| --- | --- | --- |
| 相信客户端传的宽高 | 卡片布局错乱 | 应从 `t_file` 派生 |
| `updateById(entity)` | 改标题把标签清空 | 实体里没 set 的字段变成 null |
| 空标签存 `[]` 有的人存 `null` | 统计口径不一致 | 要统一表示 |
| 给图片素材改 `content` 不拦 | 库里出现图文混合的怪数据 | 忘了类型校验 |
| `readTags` 直接抛异常 | 一条脏数据让整个列表 500 | 该降级 |
| 出参暴露 `userId` | 前端能构造越权请求 | DTO/VO 不共用 |

## 十一、自检清单

- [ ] 能说出哪四个字段是"后端派生、不接受客户端传"的，以及为什么
- [ ] 能解释 `kind` 与文件类型不匹配会导致什么现象
- [ ] 知道 `update(null, wrapper)` 为什么能刷新 `updated_at`
- [ ] 能说清 `tags` 解析失败降级、`metadata` 解析失败报错的区别
