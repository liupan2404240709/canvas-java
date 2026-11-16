# 05 · BCrypt 密码存储与登录时序

## 一句话定位

密码只能做**单向哈希**，不能加密；而哈希之后还要解决两件事：**同一密码不能产生相同哈希**（加盐）、**不能通过响应时间猜出账号是否存在**（等时校验）。

## 完整代码

```java
// SecurityConfig
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

```java
// AuthServiceImpl 构造函数
this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
```

```java
// AuthServiceImpl#login
User user = findByAccount(account);
String hash = user == null ? dummyHash : user.getPasswordHash();
boolean matched = passwordEncoder.matches(request.password(), hash);
if (user == null || !matched) {
    int remaining = loginAttemptGuard.recordFailure(account);
    ...
    throw new BizException(ResultCode.BAD_CREDENTIALS, "用户名或密码错误，还可尝试 " + remaining + " 次");
}
```

## 逐段拆解

### ① 为什么是哈希不是加密

| | 加密（AES 等） | 哈希（BCrypt） |
| --- | --- | --- |
| 可逆 | 可逆，有密钥就能还原 | 不可逆 |
| 泄露后果 | 拿到密钥 = 拿到所有明文密码 | 只能暴力破解 |
| 业务需要 | 要还原原文时用（比如支付回调报文） | 校验"输入的密码对不对" |

登录流程只需要回答"这个密码是否等于当初那个密码"，永远不需要还原明文。所以用哈希。

**如果数据库泄露**：BCrypt 密文 + 每个用户独立的盐，攻击者只能对每个用户单独跑字典，成本极高。

### ② `BCryptPasswordEncoder` 内部做了什么

```
BCrypt(password, salt, cost) → $2a$10$N9qo8uLOickgx2ZMRZoMye...
                                ↑  ↑  ↑
                             版本 成本 22 字符的盐 + 31 字符的哈希
```

- **盐是内置的**：`encode()` 每次调用都生成新的随机盐，所以同一个密码两次加密结果不同。**不需要自己拼盐**——手动拼盐（比如 MD5(password + salt)）是老写法，容易出各种问题。
- **cost = 10**：迭代 `2^10 = 1024` 轮。可配置 `new BCryptPasswordEncoder(12)`，每 +1 耗时翻倍。
- **密文自带盐**：校验时 `matches(raw, encoded)` 从密文里解出盐再算一遍，所以不需要额外存盐字段。

**为什么不用 MD5/SHA256**：它们是"快"哈希，GPU 一秒钟能算几十亿次。BCrypt 天生慢（单次几十毫秒），这是**特性不是缺点**——它把暴力破解的成本抬高了几个数量级。同理还有 scrypt、Argon2。

### ③ BCrypt 的 72 字节限制（真实存在的坑）

BCrypt 算法只处理前 **72 字节**，超出部分被静默丢弃。

- 英文数字：72 个字符就是 72 字节，`@Size(max = 64)` 足够；
- **中文**：UTF-8 下一个汉字 3 字节，24 个汉字就到 72 字节了。

所以如果密码允许中文，`@Size(max = 64)` 并不能保证不超 72 字节。当前 DTO 没限制必须 ASCII，理论上存在"超长部分不参与校验"的问题（不影响正确性，但会让"密码后半段"形同虚设）。

要彻底解决可以自己加字节长度校验：

```java
@AssertTrue(message = "密码过长")
public boolean isPasswordLengthValid() {
    return password == null || password.getBytes(StandardCharsets.UTF_8).length <= 72;
}
```

阶段 1 先记录这个已知问题，不引入额外复杂度。

### ④ 为什么要准备一个 `dummyHash`

这是防**用户枚举**的关键一步。

如果代码写成：

```java
User user = findByAccount(account);
if (user == null) throw new BizException(BAD_CREDENTIALS);   // 立即返回，约 2ms
if (!passwordEncoder.matches(...)) throw ...                 // 要算 BCrypt，约 60ms
```

攻击者不需要知道密码，只需要**测量响应时间**：快的说明账号不存在，慢的说明存在。这叫**时序侧信道**（timing side channel）。

我们的写法是：账号不存在时也拿 `dummyHash` 跑一次 `matches`，两个分支都消耗一次 BCrypt 的时间。

`dummyHash` 在构造函数里用随机 UUID 生成，**保证它不可能是任何真实密码的哈希**（UUID 没人会拿来当密码），所以 `matches` 必然返回 false。

**为什么在构造函数里生成而不是写死一个常量**：写死常量的话，理论上有人能猜到"哪个密码的哈希是这串"，虽然极难，但没有必要留这个口子。构造时生成，进程重启就变，成本只有一次 BCrypt。

### ⑤ 为什么失败文案也要统一

```java
"用户名或密码错误，还可尝试 " + remaining + " 次"
```

不能写成"该用户不存在"和"密码错误"两种文案——那是最直接的账号枚举方式。

我们的文案对两种情况完全一致，攻击者无法从文字上区分。

**但要注意**：`还可尝试 N 次` 这个信息本身是有用的（提升体验），它不泄露账号是否存在，因为不存在的账号也会计数。代价是：攻击者可以拿这个计数来做"锁定 DoS"（故意输错把别人账号锁住）。

这是安全性与可用性的取舍。当前选择"锁账号"而不是"锁 IP"，因为：
- 锁 IP 对 NAT 后的公司网络伤害大（一个人输错，全公司登不上）；
- 锁账号的影响面小，且真实用户 15 分钟后自动解除。

### ⑥ 校验顺序：先查锁定状态，再验密码

```java
long lockedSeconds = loginAttemptGuard.lockedSeconds(account);
if (lockedSeconds > 0) throw new BizException(ACCOUNT_LOCKED, ...);
```

解锁状态下如果还继续跑 BCrypt，就白白消耗 CPU——攻击者可以用 100 QPS 打过来，每个请求都算一次 BCrypt，直接把 CPU 打满。先判断锁定再做昂贵计算，是**廉价校验前置**原则。

### ⑦ 注册时为什么也 encode

```java
user.setPasswordHash(passwordEncoder.encode(request.password()));
```

注意 `encode` 是**不可逆**的，所以注册后无法"找回密码"，只能走"重置密码"（需要邮箱验证码，阶段 1 未实现）。这一点必须在产品层面说清楚，否则用户会以为能找回。

## 设计决策对比

| 决策 | 选的 | 替代 | 代价 |
| --- | --- | --- | --- |
| 哈希算法 | BCrypt | Argon2 / scrypt | BCrypt 生态成熟、Spring 原生支持；Argon2 抗 GPU 更强但要多引依赖 |
| cost | 10（默认） | 12 / 14 | 10 约 60ms，12 约 240ms；过高会让登录接口成为 CPU 瓶颈（也是 DoS 入口） |
| 盐 | 内置 | 手动拼 | 内置更安全，手写容易重复使用固定盐 |
| 时序防护 | dummyHash | 不处理 | 多一次 BCrypt 计算（约 60ms） |
| 锁定维度 | 账号 | IP / 账号+IP | 锁账号影响面小；锁 IP 会误伤 NAT 后的整个公司 |

## 面试考察点

**基础**

- 为什么密码要用 BCrypt 而不是 MD5？—— MD5 快、无盐、彩虹表可查；BCrypt 慢、自带盐。
- BCrypt 密文里包含哪些信息？—— 版本、cost、盐、哈希，共 60 字符。
- 同一密码两次 `encode` 结果相同吗？—— 不同，因为盐随机。

**进阶**

- 什么是彩虹表？加盐怎么防？—— 预计算的哈希表；盐让每个用户的哈希都不同，预计算失效。
- 为什么 BCrypt 慢反而是优点？—— 抬高暴力破解成本，且可通过 cost 参数随硬件升级调整。
- 什么是时序攻击？怎么防？—— 通过响应时间差推断信息；用等时比较（本项目用 dummyHash 让两个分支耗时接近）。
- BCrypt 72 字节限制怎么产生的？—— 算法内部只取前 72 字节，超出静默丢弃。
- cost 设置过高有什么风险？—— 登录接口变成 CPU 放大器，可被用来做资源耗尽攻击。

**陷阱**

- "我自己写 MD5(password + 固定盐)"—— 固定盐等于所有人共用一个盐，彩虹表退化成一张表；且 MD5 太快。
- "把盐存到另一个字段"—— BCrypt 不需要，密文自带盐。
- "用 `equals` 比较哈希"—— 理论上存在时序差异，应该用 `passwordEncoder.matches()`（内部是常量时间比较思路）。
- "报错文案区分用户不存在和密码错误"—— 直接的账号枚举漏洞。

## 常见坑

1. **从别的框架迁移过来的旧哈希**：`DelegatingPasswordEncoder` 支持 `{bcrypt}` 前缀，可以同时兼容多种算法。本项目从零开始，不引入。
2. **测试环境把 cost 调到 4 加速**：可以，但要在配置里区分，别带到生产。
3. **`matches(raw, null)` 会抛 IllegalArgumentException**：`dummyHash` 保证不为 null，但如果数据库里有历史脏数据（password_hash 为空）就会炸。防御性写法是先判空。
4. **密码重置后要踢掉所有会话**：本项目 refresh token 是白名单机制，重置密码时应该批量删除该用户的所有 refresh 记录。阶段 1 没做密码重置，等阶段 2 补。

## 自检清单

- [ ] 能说清哈希和加密的本质区别，以及密码为什么只能用哈希
- [ ] 知道 BCrypt 密文里包含盐，不需要额外字段
- [ ] 能解释 cost 的含义和调整策略
- [ ] 能说出 72 字节限制的成因和中文场景下的隐患
- [ ] 能解释时序攻击的原理和 `dummyHash` 的作用
- [ ] 知道登录失败文案为什么必须统一
- [ ] 能说出"锁账号"和"锁 IP"各自的优缺点
