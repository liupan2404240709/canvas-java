# 06　Redis 分布式锁

> 代码位置：`infra/lock/RedisLock.java`、`PromptServiceImpl#refresh` / `#refreshQuietly`

## 1. 不加锁会发生什么

三个入口都会触发同一个源的同步：

1. 用户在页面上点「同步」；
2. 用户点「同步全部」（内部也是逐个源同步）；
3. 定时任务扫描到它到期了。

不考虑并发时的两个具体事故：

| 事故 | 过程 |
| --- | --- |
| 重复拉取 | 两个线程同时 fetch，上游被打两次，两次都写库 |
| 差量删除打架 | A 算出的 key 集合是旧的，B 算的是新的；A 的 `deleteNotInKeys` 把 B 刚插进去的删掉 |
| 状态覆盖 | A 成功、B 失败，最后 `last_error` 可能是 B 写的，但数据是 A 写的 |

同步本身是幂等的（同样输入 → 同样结果），但**两次不同时刻的输入**交错执行，就会留下不一致的中间态。

## 2. 为什么是 Redis 锁

| 方案 | 单机 | 多实例 | 问题 |
| --- | --- | --- | --- |
| `synchronized` / `ReentrantLock` | ✅ | ❌ | 只在单 JVM 内有效 |
| `SELECT ... FOR UPDATE` | ✅ | ✅ | 事务里等网络请求 = 长事务，连接被占几十秒 |
| 消息队列串行化 | ✅ | ✅ | 为一个同步任务引入 MQ，运维成本过高 |
| **Redis `SET NX PX`（采用）** | ✅ | ✅ | 依赖 Redis 可用；锁本身不是强一致 |

选 Redis 的另一个理由：**这个项目本来就有 Redis**（阶段 1 用它存 token 白名单/黑名单）。不增加任何新的基础设施。

## 3. 三个必须做对的细节

### 3.1 用 `SET key value NX PX ttl` 一条命令

```java
Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
```

这一行底层是 `SET key value NX PX 180000`。

**错误写法**：

```java
if (setIfAbsent(key, token)) {     // ①
    expire(key, ttl);              // ②
}
```

如果进程在 ① 和 ② 之间崩溃/被 kill，这个 key 就**永不过期**——死锁。之后所有同步都被挡住，只能人工 `DEL`。

`SET ... NX PX` 是原子的：要么"设置成功且带过期时间"，要么"什么都没发生"。

### 3.2 value 存随机 token，不能是固定值

```java
String token = UUID.randomUUID().toString();
```

原因在解锁时：要确认"这把锁是我的"。

假设 A 拿到了锁，但执行超过 TTL（比如 3 分钟），锁自动过期；B 拿到同一把锁；此时 A 执行完来解锁——如果直接 `DEL key`，**A 删掉的是 B 的锁**。接着 C 又能拿到锁，于是 B 和 C 并发。

存 token 之后，A 解锁时会比对："锁里的值还是我的 UUID 吗？"不是就什么都不做。

### 3.3 解锁必须是 Lua 原子脚本

```java
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
    Long.class);
```

**错误写法**：

```java
if (token.equals(get(key))) {   // ①
    del(key);                   // ②
}
```

① 和 ② 之间锁可能过期并被别人拿走 → ② 删掉的是别人的锁。

Lua 脚本在 Redis 里是**单线程原子执行**的，比对和删除之间不可能插入别的命令。

（Java 侧也可以用 `RedisTemplate.execute(script, keys, args)` 实现，本项目就是这么做的。）

## 4. 加锁的粒度与 TTL

```java
private static final String LOCK_PREFIX = "prompt:refresh:";
private static final Duration SYNC_LOCK_TTL = Duration.ofMinutes(3);

String lockKey = LOCK_PREFIX + source.getId();
```

- **粒度**：每个源一把锁（`prompt:refresh:{sourceId}`），不是全局一把。否则 A 同步源 1 的时候，B 不能同步源 2，完全没有必要。
- **TTL 3 分钟**：单次同步 = 建连 5s + 读 20s + 写库（毫秒级）+ 余量。3 分钟足够覆盖最坏情况，又不至于让崩溃后的锁长时间挡住用户。
- **key 用数据库主键**（`source.getId()`）而不是 `source_key`：主键是数字，稳定且短；`source_key` 理论上可改（自定义源目前不允许改，但不该依赖）。

## 5. 拿不到锁怎么办：直接失败，不排队

```java
if (token == null) {
    throw new BizException(ResultCode.PROMPT_SOURCE_BUSY);
}
```

`42920 → HTTP 429`，前端提示"该源正在同步中，请稍后再试"。

**为什么不阻塞等待**：
- 同步可能持续 20 秒以上，占住一个 Tomcat 线程去等，等于把并发能力主动降低；
- 用户点两次「同步」，第二次等待 20 秒然后成功，体验和"失败重试"差不多，但前者占资源；
- 队列语义需要更多设计（排队长度、超时、公平性），收益不匹配。

**批量同步时的处理不同**（`refreshQuietly`）：拿不到锁不抛异常，而是记一条 `success=false, lastError="该源正在同步中"` 的结果继续下一个。因为批量场景不该因为一个源忙就中断整批。

## 6. 锁与幂等的关系

锁是"减少并发冲突"，不是"保证正确性"的唯一手段。真正的正确性来自：

1. `upsert + 差量删除` 本身幂等（重复执行结果一致）；
2. 摘要判断让"内容没变"的重复执行不产生写入；
3. 即使锁失效（Redis 主从切换丢锁），最坏结果是多执行一次，数据仍然收敛。

**这是有意的分层**：锁负责性能（少做无用功），幂等负责正确性（做错了能自愈）。只靠锁的系统，锁一出问题就崩。

## 7. 已知局限：单实例 Redis 的锁

```
客户端 A ──SET NX──▶ Redis 主 ──异步复制──▶ Redis 从
                        ✗ 主挂了
                     从升主（锁丢了）
```

主从切换是异步复制，主节点接受了 `SET` 但还没同步给从节点就挂了 → 新主上没有这把锁 → 另一个客户端也能拿到。

Redlock（多节点多数派）也没完全解决这个问题（有争议，Martin Kleppmann 的那篇著名反驳）。真正强一致的锁需要共识算法（ZooKeeper/etcd）。

对本项目的结论：**能接受**。因为同步是幂等的，丢锁最多多做一次无用功，不会产生错误数据。

这也是选型时应该问的问题：**"锁失效的后果是什么？"** 如果后果是"数据错乱"，就不该用 Redis 锁；如果后果是"多做一次"，就没问题。

## 8. 锁 TTL 不够长会怎样

如果同步真的超过 3 分钟（比如上游极慢 + 5000 条数据），锁会提前释放，另一个请求进来并发执行。

缓解手段：
- 看门狗（watchdog）自动续期（Redisson 的做法）：复杂，需要后台线程；
- 把 TTL 设得很长（比如 30 分钟）：崩溃后锁挡很久；
- **当前选择**：TTL 3 分钟 + 幂等兜底。真出现超长同步，日志里会有"同步异常"，用户重试即可。

---

## 面试考察点

**Q1：`SETNX` + `EXPIRE` 两步写法有什么问题？**
不是原子的。两步之间进程崩溃 → key 永不过期 → 死锁。必须用 `SET key value NX PX ttl` 一条命令。

**Q2：解锁为什么不能直接 `DEL`？**
可能删掉别人的锁。A 执行超时 → 锁过期 → B 拿到锁 → A 执行完 `DEL` 删了 B 的锁 → C 又能进来。必须"比对 token + 删除"原子执行。

**Q3：为什么用 Lua？Java 里先 get 再 del 不行吗？**
不行，get 和 del 之间锁可能过期并被别人拿走，同样是误删。Lua 在 Redis 内单线程执行，保证原子性。

**Q4：锁的 TTL 怎么定？**
覆盖"最长可能执行时间"+ 余量。这里 3 分钟（建连 5s + 读 20s + 写库）。太长会导致崩溃后长时间不可用，太短会导致锁提前释放。

**Q5：Redis 锁在主从切换时会丢吗？**
会。主从复制是异步的，`SET NX` 还没来得及同步主就挂了，新主上没有这把锁。Redlock 也不能完全解决。所以要么接受"锁失效最多多做一次"（本项目），要么用 etcd/ZooKeeper。

**Q6：为什么每个源一把锁，不是全局一把？**
锁的粒度应该等于"竞争的临界区"。不同源之间没有共享数据，串行化它们只会降低吞吐。粒度太粗是分布式锁最常见的误用。

---

## 自检清单

- [ ] 能写出 `SET NX PX` 的正确用法，并说明为什么不能用 `SETNX` + `EXPIRE`
- [ ] 能解释 token 的作用和它防的具体场景
- [ ] 能背出解锁的 Lua 脚本并解释为什么必须原子
- [ ] 能说出锁 TTL 的估算依据
- [ ] 知道"拿不到锁"时手动同步和批量同步的处理为什么不同
- [ ] 能说出 Redis 锁的已知局限和本项目的兜底方式
