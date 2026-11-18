# 阶段 4 技术说明 · 目录

阶段 4 一共 12 篇逐模块说明。顺序遵循**一条数据从"配上源"到"被同步进来"再到"被搜出来"的真实路径**：先讲表，再讲怎么把远端字节拉下来、怎么把不可控的 JSON 变成可控的行、怎么幂等地写进去、怎么保证不被并发踩、最后由定时器接管。

| 序号 | 文档 | 覆盖的源码 | 一句话 |
| --- | --- | --- | --- |
| 01 | [表结构设计](./01-表结构设计.md) | `db/schema.sql`（阶段 4 段）`PromptSource` `Prompt` | 源是聚合根、提示词是附属数据；为什么提示词不做逻辑删除 |
| 02 | [RestClient 与超时](./02-RestClient与超时.md) | `PromptHttpConfig` `PromptFetcher` `PromptProperties` | 出站请求不设超时等于给自己埋雷；为什么不用自动配置的 `RestClient.Builder` |
| 03 | [第三方 JSON 归一化](./03-第三方JSON归一化.md) | `PromptJsonParser` `PromptJson` `ParsedPrompt` | 上游 schema 有偏差时，解析层该"宽容"到什么程度 |
| 04 | [幂等同步与内容摘要](./04-幂等同步与内容摘要.md) | `PromptServiceImpl#syncOne` | ETag 条件请求 + SHA-256 内容摘要，两道闸门省掉 99% 的无谓写入 |
| 05 | [批量 upsert 与差量删除](./05-批量upsert与差量删除.md) | `PromptMapper.xml#upsertBatch` `#deleteNotInKeys` | 一条语句插 200 行；`NOT IN` 空集等于清空，这个坑要显式处理 |
| 06 | [Redis 分布式锁](./06-Redis分布式锁.md) | `RedisLock` | `SET NX PX` 只是起点，token 校验 + Lua 原子删除才是完整答案 |
| 07 | [定时调度设计](./07-定时调度设计.md) | `PromptRefreshScheduler` `PromptSourceMapper#selectDueSources` | 每行一个刷新间隔 → 调度必须是"扫描到期"而不是"整点全刷" |
| 08 | [标签缓存与版本号失效](./08-标签缓存与版本号失效.md) | `PromptTagCache` | 只缓存聚合结果不缓存列表；版本号递增代替 `keys` 扫描 |
| 09 | [搜索与稳定分页](./09-搜索与稳定分页.md) | `PromptServiceImpl#list` `PromptMapper.xml#selectTagFacet` | `JSON_TABLE` 炸标签、`JSON_CONTAINS` 过滤、三列排序解决翻页跳动 |
| 10 | [内置源播种与归属隔离](./10-内置源播种与归属隔离.md) | `BuiltInPromptSources` `PromptSourceServiceImpl#ensureBuiltIns` `#getOwned` | 懒播种 + `DuplicateKeyException` 兜底；内置源为什么只允许改两个字段 |
| 11 | [SSRF 与出站安全](./11-SSRF与出站安全.md) | `PromptUrls` `PromptFetcher` `PromptProperties#allowPrivateHost` | "让服务器去请求用户填的 URL"就是 SSRF 的标准形态 |
| 12 | [完整链路](./12-完整链路.md) | 全链路 | 列表请求 / 手动同步 / 定时同步三条时间线 |

## 面试话术总纲

只准备一段话，背这段（覆盖 02、04、05、06、07、11 的核心）：

> 提示词库这个功能本质是"定时把几个公开 GitHub 仓库的 JSON 拉进来，给用户搜索和筛选"。看着简单，但四个地方必须想清楚。
>
> 第一是**幂等**。默认 30 分钟同步一次，如果每次都是"删光重插"，主键全变、`created_at` 被重置、分页会跳、事务还长。我做了两道闸门：先发条件请求带 `If-None-Match`，远端返回 304 就直接结束，一个字节都不解析；304 没命中就把响应体算一遍 SHA-256，和库里存的 `content_sha256` 比，一样也结束。真正要写的时候用批量 upsert——一条 `INSERT ... AS new ON DUPLICATE KEY UPDATE` 插 200 行，主键靠 `(source_id, prompt_key)` 唯一索引保住；再一条 `DELETE WHERE source_id=? AND prompt_key NOT IN (...)` 删掉远端已经消失的条目。这里有个坑：`NOT IN` 空集等于永真，如果远端返回空数组又没判断，会把整源清空——所以空集要单独走"删全部"的分支，绝不能拼出空的 `NOT IN`。
>
> 第二是**并发**。手动点同步、同步全部、定时任务三个入口会撞同一个源。用 Redis 分布式锁，`SET key token NX PX 30000` 拿锁，解锁必须比对 token 再删，而且比对和删除要写在一段 Lua 里——`GET` 完再 `DEL` 是两次往返，中间锁可能已经过期被别人拿走了，这一刀就砍错人了。抢不到锁不是错误，返回"正在同步中"就行。
>
> 第三是**调度**。每个源的刷新间隔是各自配的（0/30/60/360/1440 分钟，0 表示关闭），所以不能整点全刷。我做成"每 5 分钟扫一次，捞 `last_fetched_at` 早于 `NOW() - interval` 的源"，`ORDER BY last_fetched_at IS NOT NULL, last_fetched_at` 让从没同步过的排最前，一次最多 20 个，剩下的下一轮。定时方法**不加** `@Transactional`——事务边界应该由每个源自己的同步逻辑负责，一个源失败不能连累其他源。
>
> 第四是**安全**。"用户可以填 URL，服务器去请求"这就是 SSRF。我做了三层：协议只允许 http/https；解析出的 IP 过一遍 `InetAddress` 判是不是回环/私网/链路本地（`169.254.169.254` 这种云厂商元数据地址必须挡）；响应体流式读并且限长 8MB，防止对方返回一个无限流把内存吃干净。另外还要防 URL 拼接注入——相对路径用 `URI.resolve` 而不是字符串拼接，否则一个 `//evil.com/x` 就能把 base 顶掉。

追问时的补充点：

- **为什么不缓存列表只缓存标签**：列表查询命中 `(user_id, source_id, sort_index)` 索引，本身很快；标签聚合要走 `JSON_TABLE` 炸开整张表再 `GROUP BY`，是全表级的开销，且和 keyword 无关。只缓存"某一组源下的标签计数"，被 keyword 影响的部分不缓存。
- **缓存怎么失效**：不用 `keys prompt:tags:*` 扫（`keys` 在生产上是禁用的，会阻塞单线程）。用一个版本号 `prompt:tags:ver:{userId}`，写操作 `INCR`，读的时候把版本号拼进缓存 key，版本一变旧 key 自然没人读，靠 TTL 回收。
- **空结果也要缓存**：标签集合为空时缓存一个短 TTL 的哨兵值，否则一个没数据的用户每次刷新都会打穿到 DB。这是缓存穿透的最小代价解法。
- **搜索为什么不用 `LIKE %kw%` 上全文索引**：数据量在万级、且是个人项目，`LIKE` 够了。真要上量应该换 `FULLTEXT` 或者外部搜索引擎，但那是另一个量级的复杂度，简历上我宁愿讲清楚"我知道边界在哪"。
- **分页为什么必须三列排序**：只按 `sort_index` 排，值相同的行在 MySQL 里顺序不保证；翻页时两次查询可能给出不同顺序，用户会看到同一条重复出现或者干脆漏掉。补上 `source_id, sort_index, id` 三列才稳定。
- **内置源为什么不能改 URL**：内置源是前端写死的 7 个公开仓库，作为"开箱可用"的默认值。允许改 URL 会让"内置"这个概念失去意义，而且升级时无法对齐。只开放 `enabled` 和 `refresh_interval_minutes` 两个字段。
- **播种为什么要兜底 `DuplicateKeyException`**：懒播种是"用户第一次访问时检查有没有内置源，没有就插"。多实例部署或者用户连点两下，两个请求会同时判断"没有"然后同时插入，唯一索引 `(user_id, source_key)` 会拦下第二个。这不是逻辑错误，是并发下的正常现象，捕获后忽略即可——比"先查再插"加分布式锁便宜得多。

## 阅读顺序建议

- 只想搞懂"同步为什么不重复写"：看 04 + 05。
- 只想搞懂"多个入口同时点同步会怎样"：看 06。
- 只想搞懂"服务器为什么会被人拿去扫内网"：看 11。
- 面试前一天：逐篇看末尾的自检清单，答不上来的回对应小节补。
- 想把三条链路串起来：直接读 12。
