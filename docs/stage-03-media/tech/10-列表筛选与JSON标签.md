# 10 · 列表筛选：LIKE、JSON_CONTAINS、JSON_TABLE

## 一、一句话定位

素材列表要同时支持**类型、关键字（四个字段）、标签**三种筛选，且分页稳定。这里用到两个 MySQL 8 的 JSON 函数，是这一阶段最值得讲的技术点。

## 二、核心代码

```java
LambdaQueryWrapper<Asset> wrapper = new LambdaQueryWrapper<Asset>()
        .eq(Asset::getUserId, userId)
        .eq(StringUtils.hasText(kind) && !"all".equals(kind), Asset::getKind, kind)
        .and(kw != null, w -> w.like(Asset::getTitle, kw)
                .or().like(Asset::getSource, kw)
                .or().like(Asset::getNote, kw)
                .or().like(Asset::getContent, kw))
        .apply(StringUtils.hasText(tag), "JSON_CONTAINS(tags_json, JSON_QUOTE({0}))", tag)
        .orderByDesc(Asset::getCreatedAt)
        .orderByDesc(Asset::getId);
```

生成的 SQL：

```sql
SELECT ...
FROM t_asset
WHERE deleted = 0
  AND user_id = ?
  AND kind = ?
  AND (title LIKE ? OR source LIKE ? OR note LIKE ? OR content LIKE ?)
  AND JSON_CONTAINS(tags_json, JSON_QUOTE(?))
ORDER BY created_at DESC, id DESC
LIMIT ?, ?
```

## 三、`and(...)` 里面的括号不能少

如果写成：

```java
.like(Asset::getTitle, kw)
.or().like(Asset::getSource, kw)
```

生成的是：

```sql
WHERE user_id = ? AND title LIKE ? OR source LIKE ?
```

**`AND` 优先级高于 `OR`**，等价于 `(user_id=? AND title LIKE ?) OR (source LIKE ?)` —— 用户 B 只要在 source 里匹配到关键字，就能看到用户 A 的素材。

必须用 `.and(w -> w.like(...).or().like(...))` 把 OR 组包起来。这是 MyBatis-Plus 里最容易出的越权 bug。

## 四、`like '%x%'` 还能用索引吗

**不能**（前缀未知）。`idx_asset_user_created` 会在 `user_id` 和 `deleted` 上做等值过滤，`created_at` 倒序也能用，但 `LIKE` 只能在上面的结果集里逐行过滤。

什么时候可以改写：

| 需求 | 方案 |
| --- | --- |
| 前缀搜索 `like 'x%'` | 能用索引 |
| 中文全文搜索 | MySQL 8 的 `ngram` 全文索引：`FULLTEXT KEY ft_asset_title (title) WITH PARSER ngram` |
| 复杂的多字段搜索 | Elasticsearch |
| 数据量小（万级） | `LIKE` 够用，别过度设计 |

本项目把这条写进 README 的取舍表：**数据量上万再上 FULLTEXT**。

另外注意：`LIKE` 里如果用户输入了 `%` 或 `_`，会被当成通配符。要转义得写 `ESCAPE '\\'`，MyBatis-Plus 的 `like()` 不处理这个。当前接受这个瑕疵（用户搜 `%` 会匹配全部），因为影响只是"搜索不精确"，不是安全问题。

## 五、`JSON_CONTAINS`：标签精确匹配

```sql
JSON_CONTAINS(tags_json, JSON_QUOTE(?))
```

`tags_json` 是 `["纯色","测试"]`，要判断"是否包含 `测试`"。

**错误写法：**

```sql
JSON_CONTAINS(tags_json, '测试')        -- 报错：Invalid JSON text
JSON_CONTAINS(tags_json, '"测试"')      -- 对，但字符串拼接有注入风险
tags_json LIKE '%测试%'                 -- 会误匹配 "测试用图" 里的 "测试"
```

`JSON_CONTAINS(target, candidate)` 的 `candidate` 必须是**合法 JSON 值**。字符串元素必须带双引号。

`JSON_QUOTE(?)` 的作用就是把参数 `测试` 变成 `"测试"`（含引号），从而成为合法 JSON 字符串：

```sql
JSON_QUOTE('测试')      → '"测试"'
JSON_QUOTE('a"b')       → '"a\\"b"'     -- 内部引号也会被转义
```

这样既正确又安全：**`?` 是预编译参数，不会被拼进 SQL**。MyBatis-Plus 的 `.apply(sql, values...)` 里的 `{0}` 会被替换成 `#{ew.paramNameValuePairs.MPGENVAL1}` 这种占位符，走的是标准 `PreparedStatement`。

> 绝对不要写成 `.apply("JSON_CONTAINS(tags_json, '\"" + tag + "\"')")` —— 那就是字符串拼接注入。

## 六、`JSON_TABLE`：标签聚合

需求：返回"当前用户所有标签 + 每个标签被多少条素材用过"，给筛选栏用。

`tags_json` 是一个数组，普通 SQL 没法"按标签 group by"。MySQL 8 的 `JSON_TABLE` 可以把数组**展开成行**：

```sql
SELECT jt.tag AS tag, COUNT(*) AS `count`
FROM t_asset a
JOIN JSON_TABLE (a.tags_json, '$[*]' COLUMNS (tag VARCHAR(64) PATH '$')) jt
WHERE a.user_id = ? AND a.deleted = 0 AND a.tags_json IS NOT NULL
GROUP BY jt.tag
ORDER BY `count` DESC, jt.tag ASC
LIMIT ?
```

展开过程：

```
t_asset 行: id=1, tags_json = ["纯色","测试"]
                        │
                        ▼  JSON_TABLE
                  ┌─────────┐
                  │ tag     │
                  ├─────────┤
                  │ 纯色     │
                  │ 测试     │
                  └─────────┘
                        │
                        ▼  GROUP BY
                 纯色 → 1
                 测试 → 3
```

`'$[*]'` 是 JSON Path，含义是"数组的每个元素"；`COLUMNS (tag VARCHAR(64) PATH '$')` 表示"每个元素本身作为字符串列 `tag`"。

### `JOIN ... JSON_TABLE` 是隐式 LATERAL

`JSON_TABLE` 引用了外层表 `a` 的列（`a.tags_json`），这在 MySQL 8 里是允许的——它等价于对每一行执行一次表函数（类似 PostgreSQL 的 `LATERAL`）。

所以 `tags_json IS NULL` 的行必须先过滤掉，否则 `JSON_TABLE` 对 NULL 会报错（或者返回 0 行，取决于版本）。加上 `IS NOT NULL` 最稳。

## 七、为什么不建 `t_asset_tag` 关联表

| | JSON 数组 | 关联表 |
| --- | --- | --- |
| 改标签 | 一次 UPDATE | diff 增删，一个事务里删旧插新 |
| 表数量 | 1 | 2 |
| 按标签查素材 | `JSON_CONTAINS`（不走索引） | 走索引，更快 |
| 标签重命名 | 要遍历所有行改 JSON | 改一行 |
| 标签本身有属性（颜色、排序、别名） | 没地方放 | 天然支持 |

判断标准：**标签是不是"独立实体"**。

- 本项目：标签只是素材上的几个字符串，用户手输、会重复、无属性 → JSON 数组够；
- 如果以后要做"标签管理页"（重命名、合并、颜色），就必须拆表。**那时拆，现在不拆。**

## 八、分页稳定：为什么必须两个排序键

```java
.orderByDesc(Asset::getCreatedAt)
.orderByDesc(Asset::getId)
```

`DATETIME` 精确到秒。一秒内创建的 3 条素材，如果只按 `created_at DESC` 排，MySQL 返回的相对顺序**不确定**（取决于执行计划、索引扫描方向、甚至 buffer pool 状态）。

后果：翻页时第 1 页的第 10 条和第 2 页的第 1 条可能是同一条，或者某一条永远不出现。

加上 `id DESC`（唯一键）作为第二排序键，顺序就完全确定了。

> 这是分页的通用铁律：**`ORDER BY` 的最后一列必须是唯一键**。

## 九、`size` 的静默压制

```java
long safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
```

`size=500` → 实际 100。**不报错**，只是不生效。

为什么静默：前端分页器可能有 `[10,20,50,100]` 选项，用户选 100 是对的；而脚本传 500 多半是想"一次全拿"。报错会让正常前端也收到 400（比如把 `pageSize` 传给后端时没做 clamp）。

代价是调用方不知道被压了。所以响应体里回显 `pageSize`（实际生效值），前端可以对比。

## 十、面试考察点

**Q1：`LIKE '%x%'` 为什么用不上索引？**

B+ 树索引是按前缀有序的。`LIKE 'x%'` 能定位到起始位置顺序扫描；`LIKE '%x%'` 的起始位置不确定，只能全表（或索引全扫描）逐行比较。
这就是"最左前缀原则"的另一面。

**Q2：`JSON_CONTAINS` 和 `LIKE` 查标签有什么区别？**

`LIKE '%测试%'` 会匹配 `测试用图`，`JSON_CONTAINS` 要求数组里有**完整等于** `测试` 的元素。语义精确性差别很大。
性能上两者都不走索引，但 `JSON_CONTAINS` 是二进制 JSON 比较，通常比 `LIKE` 更快且不会误匹配。

**Q3：`JSON_TABLE` 和"查出来在 Java 里统计"哪个好？**

| | `JSON_TABLE` | Java 统计 |
| --- | --- | --- |
| 传输 | 只传聚合结果（几十行） | 传全部 `tags_json`（可能几 MB） |
| 内存 | 数据库侧聚合 | 应用侧持有全量 |
| 数据库压力 | 高（CPU 在 DB） | 低 |

数据量小的时候差别不明显，但"少传一次全量"这个方向永远是对的。DB 的 CPU 通常比"应用内存 + 网络"更便宜。

**Q4：MyBatis-Plus 的 `.apply()` 会不会有 SQL 注入？**

`.apply(sql, values...)` 里的 `{0}` 会被替换成 `#{...}` 占位符，值走 `PreparedStatement`，**不会注入**。
但第一个参数（`sql` 字符串本身）是**原样拼进 SQL** 的。所以**绝对不能把用户输入拼进那个字符串**。

## 十一、常见坑

| 坑 | 现象 | 原因 |
| --- | --- | --- |
| 忘了用 `.and(w -> ...)` 包 OR | 看到别人的数据 | `AND` 优先级高于 `OR` |
| `JSON_CONTAINS(tags, ?)` 报错 | `Invalid JSON text` | 缺 `JSON_QUOTE` |
| 拼字符串做 `JSON_CONTAINS` | SQL 注入 | 没用预编译占位符 |
| 只按 `created_at` 排序 | 翻页重复/漏数据 | 缺唯一键兜底 |
| `tags_json IS NULL` 没过滤 | `JSON_TABLE` 报错或空结果 | NULL 不能展开 |
| `size` 不设上限 | 一个请求拉几万行 | 必须 clamp |
| 用 `LIKE` 查标签 | 匹配到近义标签 | 语义不精确 |

## 十二、自检清单

- [ ] 能写出"类型 + 关键字 + 标签"的组合条件 SQL
- [ ] 知道 `.and(w -> ...)` 不加会出什么事故
- [ ] 能解释 `JSON_QUOTE` 为什么必须加
- [ ] 能画出 `JSON_TABLE` 把数组炸成行的过程
- [ ] 能说出 `ORDER BY` 为什么要有唯一键兜底
