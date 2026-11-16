# 02 · JWT 签发与校验

## 一句话定位

`JwtService` 是全项目唯一碰 `io.jsonwebtoken` 的类：**签发**把用户信息打包成签名串，**解析**把签名串还原成可信的 Claims。以后换库只改这一个文件。

## JWT 的结构（先搞清楚再写代码）

```
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiIxIiwidHlwZSI6ImFjY2VzcyJ9 . 4Xy7...
      Header                    Payload                    Signature
```

| 段 | 内容 | 是否加密 |
| --- | --- | --- |
| Header | `{"alg":"HS256"}` | 明文，Base64URL 只是编码 |
| Payload | 业务声明（claims） | **明文**，任何拿到 token 的人都能 base64 解开看 |
| Signature | 对前两段的 HMAC | 用来验"有没有被改过" |

**最容易答错的一点**：JWT 只保证"不可篡改"，不保证"不可见"。所以 payload 里绝对不能放密码、手机号、身份证这类东西。我们的 token 里只有 `sub`（用户 id）、`username`、`type`、`jti`、`iss`、时间。

## 完整代码

```java
@Component
public class JwtService {

    public static final String CLAIM_TYPE = "type";
    public static final String CLAIM_USERNAME = "username";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issueAccessToken(Long userId, String username) {
        return build(userId, username, TYPE_ACCESS, properties.getAccessTokenTtl());
    }

    public IssuedToken issueRefreshToken(Long userId, String username) {
        return build(userId, username, TYPE_REFRESH, properties.getRefreshTokenTtl());
    }

    private IssuedToken build(Long userId, String username, String type, Duration ttl) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        String jti = UUID.randomUUID().toString().replace("-", "");
        String token = Jwts.builder()
                .id(jti)
                .issuer(properties.getIssuer())
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .claim(CLAIM_TYPE, type)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, jti, expiresAt, ttl.toSeconds());
    }

    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.getIssuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

## 逐段拆解

### ① `Keys.hmacShaKeyFor(...)`：密钥长度是硬约束

HS256 要求密钥至少 **256 位 = 32 字节**。`hmacShaKeyFor` 会检查长度，不够直接抛 `WeakKeyException`。

**为什么在构造函数里做**：构造函数抛异常 → Bean 创建失败 → 应用启动失败。这是"启动期快速失败"，比让一个弱密钥悄悄跑上线强得多。

**不写会怎样**：如果自己把短字符串塞给 `signWith`，jjwt 会按密钥长度自动降级到 HS128 甚至报警，安全性打折且不易察觉。

### ② 为什么 payload 里同时有 `sub` 和 `username`

- `sub`（subject）是**标准声明**，按约定放主体标识，我们放用户 id。
- `username` 是**自定义声明**，放进去是为了省一次查库：日志里可直接打出用户名，不用再 `selectById`。

**代价**：用户改名后，旧 token 里的 username 还是旧的（最长 30 分钟）。阶段 1 不允许改用户名，所以没问题；如果以后允许，要么靠 `sub` 去查库，要么接受短暂不一致。

### ③ `jti` 是整套撤销机制的基础

`jti`（JWT ID）是 token 的唯一标识，我们在签发时用 UUID 生成。有了它才能：

- 把 refresh token 写进 Redis 白名单（key 用 jti）；
- 登出时把 access token 写进黑名单（key 用 jti）。

**不写会怎样**：一串 token 没有任何唯一标识，服务端无法区分"这一串"和"那一串"，撤销就无从谈起（除非整串存下来对比，那还不如用 session）。

### ④ `type` 声明：防止两类 token 混用

access token 和 refresh token 用的是**同一把密钥**，如果不加 `type` 区分，就会出现两个安全问题：
1. 拿长期有效的 refresh token 当 access token 用 → 等于 access token 永不过期；
2. 拿 access token 去调刷新接口 → 过期后又能无限续期。

所以在签发时打标记、解析时校验，见 03 篇的过滤器判断。

### ⑤ `issuer` + `requireIssuer`

`iss` 声明"谁签的"。解析时 `requireIssuer` 会在验签通过后再校验一次 issuer，不匹配就抛 `IncorrectClaimException`。

**为什么需要**：多系统共用同一套密钥（比如公司内网多个服务从一个密钥中心取密钥）时，防止 A 系统的 token 拿到 B 系统用。当前项目只有一个服务，看起来多余，但成本是零。

### ⑥ 必须显式指定算法（实测踩到的坑）

代码里写的是：

```java
.signWith(key, Jwts.SIG.HS256)
```

如果只写 `.signWith(key)`，jjwt 会**按密钥长度自动选择算法**：

| 密钥长度 | 自动选择 |
| --- | --- |
| 32~47 字节 | HS256 |
| 48~63 字节 | HS384 |
| 64 字节以上 | HS512 |

当前 secret 是 52 字节，实测签出来的 token 头是 `{"alg":"HS384"}`——和文档、和"HS256"这个设计预期都不一致。这种行为**隐蔽且不可控**：运维把密钥改长一点，算法就变了，而 token 内容、有效期、代码全都没动，排查时极易误判。

所以显式指定算法，让"签什么算法"由代码决定，而不是由密钥长度决定。

**注意**：算法只在签发时指定，校验时 `verifyWith(key)` 会读 token 头里的 `alg` 并检查密钥长度是否满足要求。所以密钥一旦换短，**已经签发的老 token 会解析失败**（密钥长度不够该算法），这也是一个需要提前知道的行为。

### ⑦ 为什么返回 `IssuedToken` 而不是光返回字符串

调用方需要三样东西：token 本身（给前端）、jti（写 Redis 白名单）、过期时间（算黑名单 TTL）。如果只返回字符串，调用方得再解析一遍 token 才能拿到 jti——既浪费又要处理解析异常。

### ⑧ HS256 vs RS256 怎么选

| | HS256（对称） | RS256（非对称） |
| --- | --- | --- |
| 密钥 | 一把，签发方和校验方共享 | 私钥签发、公钥校验 |
| 适用 | 单体、签发和校验是同一个服务 | 多服务校验、第三方要验你的 token |
| 风险 | 任何能验签的服务都能伪造 token | 公钥泄露不影响安全 |

当前是单体后端，选 HS256。如果以后拆出网关、多个微服务都要验 token，再换 RS256——`JwtService` 里换 `signWith/verifyWith` 两行即可。

## 设计决策对比

| 决策 | 选的 | 替代 | 代价 |
| --- | --- | --- | --- |
| 算法 | HS256 | RS256 | 换取实现简单，代价是无法安全地分发验签能力 |
| 密钥来源 | yml 配置 | 环境变量 / KMS | 开发方便，**生产必须改成环境变量** |
| 有效期存放 | 配置 `Duration` | 写死在代码 | 配置可调，代价是多一个配置项要维护 |
| 自定义声明 | 扁平 claim | 整个对象塞进 claim | 扁平更省体积（token 每次请求都要传输） |

## 面试考察点

**基础**

- JWT 三段分别是什么？签名签的是哪部分？—— 前两段（Header.Payload）的 base64 拼接串。
- JWT 能被解密吗？—— 不能解密但**能被解码**，payload 是明文，所以不能放敏感信息。
- `exp` / `iat` / `nbf` / `jti` / `sub` / `iss` 各是什么含义？

**进阶**

- 为什么 JWT 能防篡改？—— HMAC 需要密钥，改任何一个字节签名都对不上。
- HS256 和 RS256 的本质区别？什么场景必须用 RS256？
- token 里为什么放 `type`？—— 防止两类令牌混用导致提权。
- 密钥太短会怎样？—— `WeakKeyException`，启动失败。
- `alg: none` 攻击是什么？—— 早期库允许把 alg 改成 none 从而跳过验签；jjwt 0.12 的 `parseSignedClaims` 只接受签名 token，天然免疫。

**陷阱**

- "我把用户对象整个塞进 token"—— 体积膨胀（每个请求都带）、敏感字段泄露、信息过期。
- "JWT 是加密的，可以放手机号"—— 错误认知，payload 明文可见。
- "token 有效期设 7 天省事"—— access token 有效期越长，泄露后窗口越大；长有效期应该交给 refresh token。

## 常见坑

1. **服务器时间不同步**：`exp` 校验依赖本机时钟，容器时间漂移会导致"刚签发就过期"。生产要配 NTP。
2. **密钥硬编码进仓库**：当前 yml 里是开发密钥，`.gitignore` 挡不住已经提交的历史。生产用 `APP_JWT_SECRET` 环境变量覆盖。
3. **改了密钥后旧 token 全部失效**：这是预期行为，但如果多实例部署时各实例密钥不一致，会出现"一会儿能访问一会儿 401"的诡异现象。
4. **`signWith(key)` 会按密钥长度换算法**：52 字节的密钥签出来是 HS384（本项目实测），必须显式传 `Jwts.SIG.HS256`。
5. `claims.get("username", String.class)` 泛型参数不能省，否则拿到的是 `Object`，强转会 ClassCastException。

## 自检清单

- [ ] 能画出一串 JWT 的三段结构，并说明签名覆盖范围
- [ ] 能解释为什么 payload 不能放敏感信息
- [ ] 知道 `jti` 是干什么的、为什么撤销机制离不开它
- [ ] 能说出 `type` 声明防住的两个具体攻击
- [ ] 能对比 HS256 / RS256 的适用场景
- [ ] 知道密钥长度的硬性要求（32 字节）和失败表现
- [ ] 能说出至少两个 JWT 的固有缺陷（不可撤销、体积、信息过期）
