# 03　第三方 JSON 归一化

> 代码位置：`infra/prompt/PromptJsonParser.java`、`infra/prompt/PromptUrls.java`、`infra/prompt/ParsedPrompt.java`

## 1. 输入长什么样

上游是人工维护的 GitHub 仓库产物，同一个 schema 在不同仓库里都有偏差：

```json
[
  {
    "id": "product-photo-1",
    "title": "Product photo",
    "prompt": "Generate a professional product photo ...",
    "description": "",
    "coverUrl": "./images/cover-1.png",
    "referenceImageUrls": ["./images/cover-1.png"],
    "tags": ["product", "white-background"],
    "preview": "...",
    "createdAt": "2025-03-11T09:20:00Z",
    "updatedAt": "2025-06-02",
    "sourceUrl": "https://github.com/.../1",
    "imageCount": 2
  }
]
```

实际遇到的偏差（写代码前就该假设会有的）：

| 偏差 | 例子 | 不处理的后果 |
| --- | --- | --- |
| 缺 `title` 或 `prompt` | 只给了 `description` | 列表出现空卡片 |
| 缺 `id` | 老数据 | upsert 没有唯一键，每次同步都重复插入 |
| `tags` 不是数组 | `"tags": "人像,暖色"` | `JSON_CONTAINS` 永远不命中 |
| 相对路径 | `"./images/a.png"` | 前端 `<img>` 404 |
| 时间格式五花八门 | 带 Z / 只有日期 / 空串 | `new Date()` 得到 Invalid Date |
| 字段超长 | `prompt` 几十 KB、`title` 上千字 | 严格模式下整批 INSERT 失败 |
| 顶层不是数组 | 返回 `{"items":[...]}` | 解析崩 |

## 2. 一条总原则：能救就救，救不了就跳过

```
顶层不是数组            → 整体失败（格式不兼容，没法猜）
单条缺 title/prompt     → 丢弃这一条，继续下一条
单条字段类型不对        → 转成安全的默认值（空数组/空串/null）
单条超长                → 截断到列宽
```

对比另一种做法："发现任何一条不合法就整份拒绝"。那会导致一个源的 500 条里有一条脏数据，整次同步失败——而用户完全无法修（数据在上游）。

**批量同步和单条校验的要求是相反的**：单条接口应该严格（用户能改），批量同步应该宽容（用户改不了）。

## 3. 逐项实现

### 3.1 顶层校验

```java
JsonNode root = objectMapper.readTree(body);
if (root == null || !root.isArray()) {
    throw new PromptSyncException("返回内容不是 JSON 数组（顶层必须是 [ ... ]）");
}
```

`readTree` 对非法 JSON 会抛 `JsonProcessingException`，转成 `PromptSyncException` 并把 `getOriginalMessage()` 带上（"Unexpected character ..." 比堆栈有用）。

**为什么不容忍 `{"items":[...]}`**：猜字段名（`items` / `data` / `list` / `prompts`）是无底洞，而且猜错了会静默得到 0 条。明确报错，让用户去改源。

### 3.2 必填字段

```java
String title = cut(text(node, "title").trim(), MAX_TITLE);
String prompt = text(node, "prompt").trim();
if (title.isEmpty() || prompt.isEmpty()) continue;
```

注意 `prompt` 不截断（`MEDIUMTEXT` 16MB 够用），`title` 截断到 300（列宽）。

### 3.3 缺失的 id 要补，而且必须稳定

```java
String key = cut(text(node, "id").trim(), MAX_KEY);
if (key.isEmpty()) {
    key = sourceKey + "-" + String.format("%04d", index);
}
```

为什么用"序号"而不是 UUID：**幂等**。同一个源每次同步，第 3 条的 key 永远是 `{sourceKey}-0003`，upsert 才能命中"更新"而不是"新增"。用 UUID 的话每次同步都会插入一批新行、再删掉旧行，等于退化成整份替换。

代价：如果上游中间插入了一条，后面的 key 会整体偏移一位（第 3 条变成第 4 条的内容）。可接受——只有"上游本来就没给 id"的源才会走到这条路径。

### 3.4 去重

```java
LinkedHashSet<String> seen = new LinkedHashSet<>();
if (!seen.add(key)) continue;
```

用 `LinkedHashSet` 而不是 `HashSet`：保留插入顺序（后面的 `sort_index` 依赖它）。

上游偶尔会有重复 id（复制粘贴造成）。重复的直接跳过第一条之后的——比让 `uk_prompt_source_key` 报错好，因为整批 insert 会失败。

### 3.5 相对 URL 补全

```java
public static String resolve(String baseUrl, String reference) {
    return URI.create(baseUrl).resolve(reference.trim()).toString();
}
```

基准是**源的 JSON 地址**，不是 `homepage`。因为 `./images/a.png` 是相对"这个 JSON 文件所在目录"的。

```
源 URL:  https://raw.githubusercontent.com/x/y/main/dist/sources/awesome.json
相对路径: ./images/a.png
结果:     https://raw.githubusercontent.com/x/y/main/dist/sources/images/a.png
```

`URI.resolve` 会正确处理 `.`、`..`、绝对路径（`/x/y.png`）、以及本身就是绝对 URL 的情况（直接返回）。

### 3.6 时间解析：四段降级

```java
OffsetDateTime.parse(value)   // 2025-03-11T09:20:00Z、+08:00
→ LocalDateTime.parse(value)  // 2025-03-11T09:20:00
→ LocalDate.parse(value)      // 2025-03-11
→ Long.parseLong(value)       // 毫秒时间戳
```

全失败 → `null`（前端显示空）。

为什么不用 `SimpleDateFormat` 一次性搞定：它对格式极其挑剔（多一个字符就抛异常），而且不是线程安全的。`java.time` 的 `parse` 抛异常是可捕获的，逐个试最直观。

为什么解析不出来不报错：时间只用于展示"这是什么年代的提示词"，丢了不影响搜索和使用。**为展示字段让整条数据失败，不值得**。

### 3.7 数组字段的宽容处理

```java
private List<String> stringArray(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) return List.of();
    ...
}
```

`tags: "人像,暖色"`（字符串）→ 空数组。虽然丢了信息，但比"存进去一个非法 JSON 导致 `JSON_CONTAINS` 永远 false"好。要更聪明可以按逗号切分——但那是在猜格式，不如明确"只认数组"。

### 3.8 截断

```java
private String cut(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
}
```

截断到列的字符限制。不截断的后果：MySQL 严格模式（默认）下超长直接报错，整批 INSERT 失败；非严格模式会静默截断并给 warning——两种都不如"我主动截断"可控。

注意 `substring` 按 `char` 切，可能把一个 emoji 的代理对切成两半。极端情况下会出现乱码字符。真实数据里 `title` 超 300 字符的情况很少，为了一个 emoji 引入 codePoint 遍历不值得（真要处理得用 `value.codePointCount` + `offsetByCodePoints`）。

## 4. 单源条数上限 5000

```java
private static final int MAX_ITEMS = 5000;
```

原因不是"防止用户"，而是**一次 upsert 的包大小**：每行包含 `prompt`（可能几千字），5000 行轻松超过 `max_allowed_packet`（默认 64MB）。

真正的防线在服务层：`UPSERT_BATCH = 200`，把插入切成 200 行一批。`MAX_ITEMS` 是"总量上限"，防止一个源把库撑爆。

## 5. 输出：`ParsedPrompt` 而不是实体

```java
public record ParsedPrompt(String promptKey, String title, String prompt, ...,
                           LocalDateTime remoteCreatedAt, LocalDateTime remoteUpdatedAt) {}
```

为什么不直接返回 `Prompt` 实体：

- 实体里有 `id`（雪花）、`userId`、`sourceId`，解析阶段根本不知道这些；
- 实体的 JSON 字段是 `String`，解析阶段手里是 `List<String>`，两边来回转很别扭；
- 解析关心"源里有什么"，入库关心"表里存什么"。中间隔一层，以后换表结构不用动解析。

**这是"输入模型"和"持久化模型"分离**。小项目里常被省略，但一旦解析逻辑超过 50 行，混在一起就会很难读。

---

## 面试考察点

**Q1：批量导数据时，遇到单条格式错误应该整批失败还是跳过？**
跳过 + 记录。因为用户改不了上游数据，整批失败等于功能不可用。反过来，用户自己提交的表单（单条）应该严格校验并明确报错——他能改。

**Q2：上游没给 id 时，为什么不能用 UUID 补？**
因为幂等同步依赖"同一条数据每次同步得到同一个 key"。UUID 每次都不同，upsert 永远命中不了"更新"，行为退化成"全删全插"。用"源 key + 序号"才稳定。

**Q3：相对 URL 用什么做基准？**
源的 JSON 文件地址，不是 homepage。`./images/a.png` 是相对 JSON 文件所在目录的。用 `URI.resolve` 而不是字符串拼接，它会正确处理 `..` 和绝对路径。

**Q4：为什么 `prompt` 字段不截断而 `title` 截断？**
`prompt` 存在 `MEDIUMTEXT`（16MB），真实数据远小于此；`title` 是 `VARCHAR(300)`，超了整批 INSERT 会失败。**截断只用在列有硬限制的地方**。

**Q5：`ParsedPrompt` 和实体分开，除了"职责清晰"还有什么实际好处？**
解析可以独立单测（不需要数据库、不需要雪花 ID）；未来给 `t_prompt` 加字段时，解析层不用跟着改；解析结果能直接用于"预检"（比如先算条数再决定要不要写库）。

---

## 自检清单

- [ ] 能列出至少 4 种上游数据偏差，并说出处理策略
- [ ] 能解释为什么缺 id 时补"序号"而不是 UUID
- [ ] 能写出相对 URL 补全的代码，并说明基准 URL 是哪个
- [ ] 知道时间解析失败应该返回 null 而不是抛异常
- [ ] 能解释 `MAX_ITEMS` 和 `UPSERT_BATCH` 两个上限分别防什么
