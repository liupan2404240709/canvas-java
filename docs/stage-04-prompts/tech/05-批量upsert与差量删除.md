# 05　批量 upsert 与差量删除

> 代码位置：`resources/mapper/PromptMapper.xml`（`upsertBatch` / `deleteNotInKeys`）、`PromptServiceImpl#upsertPrompts`

## 1. 三种写入策略

| 策略 | SQL 形态 | 优点 | 缺点 |
| --- | --- | --- | --- |
| 整份替换 | `DELETE WHERE source_id` + 批量 `INSERT` | 最简单 | 主键变、`created_at` 重置、分页跳、长事务 |
| 逐条 upsert | 循环 N 次 `INSERT ... ON DUPLICATE` | 逻辑最简单 | N 次网络往返，5000 条要几分钟 |
| **批量 upsert + 差量删（采用）** | 一条语句插 200 行，再一条 `NOT IN` 删除 | 往返少、主键稳定 | SQL 要写 `<foreach>`，要注意包大小 |

第三种是"性能"和"数据稳定性"的平衡点。

## 2. 批量 upsert 的 SQL

```xml
<insert id="upsertBatch">
    INSERT INTO t_prompt (id, user_id, source_id, prompt_key, title, prompt, ...)
    VALUES
    <foreach collection="items" item="p" separator=",">
        (#{p.id}, #{p.userId}, #{p.sourceId}, #{p.promptKey}, ..., NOW())
    </foreach>
    AS new
    ON DUPLICATE KEY UPDATE title = new.title,
                            prompt = new.prompt,
                            ...
                            remote_updated_at = new.remote_updated_at
</insert>
```

### 2.1 为什么是 `AS new` 而不是 `VALUES(col)`

MySQL 8.0.20 起 `VALUES(col)` 被标记为 deprecated，官方推荐行别名：

```sql
-- 旧写法（会刷 deprecation warning）
ON DUPLICATE KEY UPDATE title = VALUES(title)

-- 新写法（8.0.19+）
INSERT INTO t (...) VALUES (...) AS new
ON DUPLICATE KEY UPDATE title = new.title
```

语义上 `new` 就是"这次试图插入的那一行"。当前数据库是 8.0.46，用新语法。

注意：别名要放在**整个 VALUES 列表之后**，不是每一行之后。

### 2.2 为什么 `created_at` 用 `NOW()` 且不放进 UPDATE 列表

```sql
INSERT INTO t_prompt (..., created_at) VALUES (..., NOW())
ON DUPLICATE KEY UPDATE title = new.title, ...   -- 注意没有 created_at
```

- 新行：`created_at` = 现在（首次入库时间）；
- 已存在的行：`created_at` **保持不变**（这正是 upsert 相对整份替换的核心优势）。

`id` 同理：`ON DUPLICATE KEY UPDATE` 不碰 `id`，所以已存在的行主键稳定。

### 2.3 为什么每批 200 行

```java
private static final int UPSERT_BATCH = 200;
```

两个约束：
- `max_allowed_packet`（MySQL 默认 64MB）：200 行 × 假设 5KB = 1MB，很安全；
- 单条 SQL 的解析开销与行数超线性增长：200 行是实践中常见的折中值。

太小的坏处：往返次数多（5000 条 → 25 次）。太大了坏处：包超限直接失败，且失败时整批回滚。

### 2.4 主键从哪来

```java
prompt.setId(IdWorker.getId());
```

`IdWorker` 是 MyBatis-Plus 的雪花实现。这里必须自己生成：XML 里的手写 `INSERT` 不经过 MyBatis-Plus 的 `IdType.ASSIGN_ID` 自动填充（那个机制只在 `insert(entity)` 时生效）。

对已存在的行，这个 id 会被忽略（`ON DUPLICATE KEY UPDATE` 不更新 `id`）。所以"生成一堆用不上的 id"是没有代价的。

## 3. 差量删除

```xml
<delete id="deleteNotInKeys">
    DELETE FROM t_prompt
    WHERE source_id = #{sourceId}
    <if test="keys != null and keys.size() > 0">
        AND prompt_key NOT IN
        <foreach collection="keys" item="key" open="(" separator="," close=")">#{key}</foreach>
    </if>
</delete>
```

### 3.1 为什么需要它

上游会删条目。不做差量删除，本地就永远保留"源里已经不存在"的提示词——用户点了原始链接会发现 404。

### 3.2 `keys` 为空时的语义

`<if>` 没有 `else`：`keys` 为空时退化成"删除该源的全部提示词"。

这个行为是**有意的**：上游返回空数组 = "整份列表被清空了"。
- 内置源不会走到这里（`parsed.isEmpty()` 且 `built_in=1` 时直接抛异常，保护数据）；
- 自定义源允许（用户自己填的源，返回空数组是合理的）。

如果写成"keys 为空就不删"，那么上游清空列表后本地会残留全部旧数据，而且**永远不会被清掉**。

### 3.3 为什么不写成 `NOT EXISTS` 子查询

```sql
DELETE FROM t_prompt p
WHERE p.source_id = ?
  AND NOT EXISTS (SELECT 1 FROM (...) AS t WHERE t.prompt_key = p.prompt_key)
```

需要先把"新的一批 key"塞进 SQL 里（临时表/派生表），MySQL 语法繁琐且优化器行为不好预测。而 `keys` 本来就在内存里（刚解析出来的 `List<ParsedPrompt>`），直接 `NOT IN` 最直观。

数量级：几千个 key 的 `NOT IN` 完全没问题（MySQL 的解析上限远高于此）。

### 3.4 顺序：先 upsert 再删除

```java
transactionTemplate.executeWithoutResult(status -> {
    upsertPrompts(source, parsed);                                    // 先保证新数据在
    promptMapper.deleteNotInKeys(source.getId(), keys);               // 再删旧的
    markSuccess(...);
});
```

如果在同一个事务里先删再插，中途失败（比如包太大、唯一键冲突）会回滚，看起来没问题。但如果哪天事务边界被改错（比如把删除放到事务外），"先删后插"就会留下空库。

**"先加后减"是数据同步的通用安全顺序**：任何时刻中断，本地都是"旧数据 + 新数据"，而不是"什么都没有"。

## 4. 事务边界

```java
transactionTemplate.executeWithoutResult(status -> { ... });
```

为什么用 `TransactionTemplate` 而不是在方法上加 `@Transactional`：

`syncOne` 里有网络请求（`promptFetcher.fetch`）。如果把整个方法标成 `@Transactional`，事务会**包住那次 HTTP 请求**：一个源响应慢 20 秒，数据库连接就被占 20 秒。7 个源串行同步，连接池（默认 10）会被打空。

所以事务只包住"写库那三步"，网络请求在事务外：

```
[事务外] fetch（最长 20s）
[事务内] upsert + delete + update source   ← 毫秒级
```

这是**长事务的经典问题**：把 RPC/HTTP 放进事务里，等于把外部系统的延迟转嫁给了数据库连接。

同样地，这也是为什么 `PromptRefreshScheduler` 的方法上没有 `@Transactional`。

## 5. 与"整份替换"的取舍再确认

阶段 2 的画布快照用的是**整份替换**（`DELETE` 全部 + 批量 `INSERT`）。为什么这里不用同样的策略？

| 维度 | 画布快照 | 提示词同步 |
| --- | --- | --- |
| 数据来源 | 前端提交的完整状态 | 上游 JSON |
| 变化频率 | 用户每次拖拽/保存 | 一周几次 |
| 单次数据量 | ≤500 节点 | 可能几千条 |
| 主键稳定性要求 | 不需要（对外用 `node_key`） | 需要（分页锚点、`created_at`） |
| 内容不变时 | 不会发生（用户主动保存） | 经常发生（90% 的同步） |

**同一个问题，在不同的约束下答案不同**。这也是设计文档里为什么要写"为什么"而不是只写"是什么"。

---

## 面试考察点

**Q1：`INSERT ... ON DUPLICATE KEY UPDATE` 的判重依据是什么？**
唯一索引或主键。这里靠 `uk_prompt_source_key (source_id, prompt_key)`。注意：如果表上有多个唯一索引，任一冲突都会走 UPDATE。

**Q2：批量插入为什么不能一次插完？**
`max_allowed_packet` 限制 + SQL 解析成本超线性 + 失败时整批回滚。分批（200 行）是折中。

**Q3：upsert 时 `id` 会被覆盖吗？**
不会。`ON DUPLICATE KEY UPDATE` 只更新列出的列，`id` 不在列表里。所以已存在行保持原主键，这正是我们要的。

**Q4：为什么把 HTTP 请求放在事务外？**
否则事务时长 = 网络延迟。数据库连接是稀缺资源（默认 10 个），几个慢源就能把连接池打空，所有接口都被拖死。这是"长事务"最常见的一种形态。

**Q5：`NOT IN` 列表很长会不会有问题？**
几千个元素没问题。真正要注意的是：`NOT IN` 遇到 NULL 时整个条件为 NULL（不成立），所以 `prompt_key` 必须 `NOT NULL`——表定义里保证了。

**Q6：如果同步过程中服务重启了会怎样？**
事务回滚，本地保持旧数据；`last_fetched_at` 也没更新成功，下一轮扫描会重试。**同步是幂等的**，重试无害。

---

## 自检清单

- [ ] 能写出批量 upsert 的 XML，并说出 `AS new` 与 `VALUES()` 的区别
- [ ] 知道 `created_at` 为什么不在 UPDATE 列表里
- [ ] 能解释 `deleteNotInKeys` 在 keys 为空时的语义
- [ ] 能说出"先 upsert 后删除"的原因
- [ ] 能解释为什么事务只包住写库，不包网络请求
- [ ] 能对比画布快照（整份替换）和提示词同步（upsert）的取舍
