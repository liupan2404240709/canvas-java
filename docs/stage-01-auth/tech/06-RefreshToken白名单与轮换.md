# 06 · Refresh Token 白名单与令牌轮换

## 一句话定位

JWT 的致命缺陷是"签发后无法撤销"。`TokenStore` 用 Redis 补上这个能力：**refresh token 用白名单（必须有记录才有效）**，**access token 用黑名单（有记录就失效）**。

## 完整代码

```java
@Component
public class TokenStore {

    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";

    private final StringRedisTemplate redis;

    public void saveRefreshToken(String jti, Long userId, Duration ttl) {
        redis.opsForValue().set(REFRESH_PREFIX + jti, String.valueOf(userId), ttl);
    }

    public Long findRefreshTokenOwner(String jti) {
        String value = redis.opsForValue().get(REFRESH_PREFIX + jti);
        return value == null ? null : Long.valueOf(value);
    }

    public void revokeRefreshToken(String jti) {
        redis.delete(REFRESH_PREFIX + jti);
    }

    public void blacklistAccessToken(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redis.opsForValue().set(BLACKLIST_PREFIX + jti, "1", ttl);
    }

    public boolean isAccessTokenBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(BLACKLIST_PREFIX + jti));
    }
}
```

## 逐段拆解

### ① 为什么 refresh 用白名单、access 用黑名单

| | access token | refresh token |
| --- | --- | --- |
| 有效期 | 30 分钟 | 14 天 |
| 数量 | 每 30 分钟一条新的 | 每次登录一条 |
| 请求频率 | 每个接口请求都带 | 每 30 分钟用一次 |
| 选择 | **黑名单** | **白名单** |

关键在数量级：

- 如果 access 也用白名单，那每个请求都要查"这个 jti 在不在白名单里"，而白名单需要**为每一个签发过的 token 保留记录**——一天下来几百万条，Redis 直接爆。
- 如果 refresh 也用黑名单，那"未撤销"是默认状态，登出时把它加黑名单。听起来也行，但白名单有个额外好处：**能实现"全部强制下线"**——只要让某用户的所有 refresh 记录失效（或者干脆换一个 key 前缀版本号），所有 refresh 立刻不可用。用黑名单做不到"批量拉黑未知的一批 token"。
- refresh 数量少（每设备每次登录一条），存白名单成本可控。

### ② 为什么 value 存 `userId` 而不是 `"1"`

白名单里存 userId 有两个用途：
1. 校验这个 refresh token **确实属于这个用户**（防止用 A 的 refresh token 换出 B 的访问权——虽然签名已经保证 sub 不可改，但多一层校验更稳）；
2. 登出时判断"要撤销的 refresh token 是不是当前用户自己的"，避免**拿别人的 refresh token 把别人踢下线**。

### ③ TTL 必须和 token 有效期一致

```java
redis.opsForValue().set(key, value, ttl);
```

`ttl` 传的是 `jwtService.refreshTokenTtl()`（14 天）。这样：

- token 过期 → Redis 记录也过期，不会留下永久垃圾；
- 不需要额外的定时清理任务。

**不设 TTL 会怎样**：Redis 里无限增长，几个月后 `keys auth:refresh:*` 能扫出几百万条，内存告急。

### ④ 黑名单 TTL = 剩余寿命，这是个技巧

```java
Duration ttl = Duration.between(Instant.now(), expiresAt);
```

如果固定写 30 分钟，那么"还剩 1 分钟就过期的 token"也会占 30 分钟空间；如果写 14 天，那空间浪费更严重。

正确做法是**让黑名单记录和 token 同生共死**：token 过期之后签名校验自然失败，黑名单就没必要存在了，所以 TTL 设成剩余时间，到期自动清理。

**注意判空**：如果 token 已经过期（`ttl` 为负），直接 return，不要写一条 TTL 为负的记录——Redis 会报错或立即删除，行为不一致。

### ⑤ `hasKey` vs `get`

```java
return Boolean.TRUE.equals(redis.hasKey(...));
```

- `hasKey` 只判断存在性，不传 value，网络开销更小（虽然我们存的就是 `"1"`）。
- `Boolean.TRUE.equals(...)` 是防御性写法：`hasKey` 返回 `Boolean` 对象，理论上可能为 null（Redis 连接异常时）。

**更好的选择**：`redis.hasKey()` 在集群模式下会走 `EXISTS`，但如果 key 很大，`EXISTS` 也只是一次 O(1) 查找，没问题。

### ⑥ 轮换（rotation）：为什么旧 refresh 要立刻作废

```java
// AuthServiceImpl#refresh
tokenStore.revokeRefreshToken(jti);
return issueTokens(user);
```

每次刷新都"作废旧 token + 签发新 token"，这叫 refresh token rotation。

**价值**：refresh token 有效期 14 天，如果泄露，攻击者可以长期冒用。轮换之后，旧 token 只能被用一次：
- 正常用户每次刷新都换新的，旧的自然作废；
- 攻击者用了旧 token → 白名单里没有了 → 失败，同时说明"这个 token 被用过了"，是**入侵信号**。

**进阶（未实现）**：真正的重放检测需要"家族"概念——如果检测到旧 token 被使用，说明它可能被盗，应该把该用户**所有** refresh token 全部撤销，强制重新登录。当前代码只返回 `TOKEN_REVOKED` 并打一条 warn 日志：

```java
log.warn("refresh token 不在白名单中，疑似重放 jti={} userId={}", jti, claims.getSubject());
```

这条日志就是后续做告警的钩子。

### ⑦ 为什么 access token 也要黑名单

理论上 access 只有 30 分钟，登出后"等 30 分钟自然失效"也能接受。但产品上不能接受：用户点了登出，如果别人拿着之前的请求记录还能继续操作 30 分钟，这是明显的安全漏洞。

所以登出时把当前 access token 的 jti 写进黑名单，配合 03 篇过滤器里的 `isAccessTokenBlacklisted` 检查，实现"立即失效"。

### ⑧ 为什么用 `StringRedisTemplate` 而不是 `RedisTemplate<String, Object>`

项目里已经有 `RedisTemplate<String, Object>`（JSON 序列化，见阶段 0 的 07 篇）。但这里的 value 全是纯字符串（userId、`"1"`），用 `StringRedisTemplate`：

- 少一层 JSON 包装，省空间；
- 直观，`redis-cli` 里直接可读；
- 反序列化不会因为 `@class` 字段缺失而失败（`GenericJackson2JsonRedisSerializer` 的坑）。

## 设计决策对比

| 决策 | 选的 | 替代 | 代价 |
| --- | --- | --- | --- |
| 撤销机制 | Redis 白+黑名单 | 纯 JWT 不可撤销 | 换来"立即登出"，代价是引入 Redis 依赖 |
| refresh 存储 | 白名单 | 黑名单 | 能批量踢人，代价是每个 refresh 都要写一次 Redis |
| key 设计 | `业务:类型:jti` | `jti` 直接做 key | 加前缀便于按类型扫描、便于隔离不同环境 |
| 登出撤销范围 | 当前 token + 传入的 refresh | 该用户全部 token | 当前实现无法"踢掉其他设备"，需要额外索引 |
| 过期清理 | 靠 Redis TTL | 定时任务扫 | TTL 零维护，成本是必须算准时长 |

## 面试考察点

**基础**

- JWT 为什么无法撤销？—— 无状态，服务端不记录已签发的 token，只能靠"有效期到了自然失效"。
- 常见补偿方案有哪些？—— 黑名单、白名单、短有效期 + refresh token、版本号（用户表存 `tokenVersion`，改密码时 +1，校验时比对）。
- Redis 的 key 过期是怎么实现的？—— 惰性删除 + 定期抽样删除。

**进阶**

- 为什么 refresh 用白名单，access 用黑名单？—— 数量和频率的量级差异（见 ①）。
- 黑名单 TTL 为什么不能固定？—— 要跟 token 剩余寿命对齐，否则要么浪费空间要么提前失效。
- 什么是 refresh token rotation？为什么要做？—— 每次刷新换新，旧的一次性作废，泄露只能被用一次。
- 多设备登录怎么处理？—— 当前实现是"每个 refresh token 独立"，多设备互不影响；要做"只能一个设备在线"，需要在用户维度存一个"当前设备 jti"。
- Redis 挂了会怎样？—— 白名单校验失败 → 所有刷新请求 401；黑名单校验失败（抛异常）→ 请求 500。生产要考虑降级策略（比如黑名单查询失败时放行并告警，因为 access 只有 30 分钟）。

**陷阱**

- "登出后 token 还能用"—— 只删了 refresh，没做 access 黑名单。
- "黑名单 key 用原始 token"—— token 长度 200+ 字符，做 key 浪费内存，而且日志里会打出完整 token（信息泄露）。
- "把 refresh token 也放进 localStorage"—— XSS 时两个 token 一起被偷。更安全的做法是 refresh 放 HttpOnly Cookie，阶段 9 接前端时讨论。

## 常见坑

1. **Redis 序列化不一致**：如果用 `RedisTemplate<String,Object>` 存了 JSON（带引号），再用 `StringRedisTemplate` 读，会读到 `"1"` 而不是 `1`，`Long.valueOf` 直接抛异常。本项目统一用 `StringRedisTemplate`，没有这个问题。
2. **`Duration` 传负数**：`opsForValue().set(key, value, negativeDuration)` 会抛 `IllegalArgumentException`。
3. **多实例时钟不一致**：黑名单 TTL 用本机 `Instant.now()` 算，容器时间漂移会让 TTL 偏长或偏短，偏差在秒级，可接受。
4. **登出必须幂等**：重复登出、refresh token 已过期，都不应该报错。所以 `AuthServiceImpl#logout` 里 catch 了 `JwtException` 只打 debug 日志。

## 自检清单

- [ ] 能解释 JWT 为什么不能撤销，以及几种补偿方案
- [ ] 能说清白名单和黑名单各自适用的 token 类型及原因
- [ ] 知道黑名单 TTL 为什么要按剩余寿命设
- [ ] 能解释 refresh token rotation 的价值和"重放检测"的思路
- [ ] 知道 key 的设计规范（业务前缀）
- [ ] 能说出当前实现做不到什么（比如踢掉其他设备）
- [ ] 能回答"Redis 挂了这套机制会怎样"
