# 阶段 1 设计文档：账号体系

> 面向"为什么这么设计"，实现细节看 `tech/`，接口契约看 `api.md`。

## 一、问题定义

阶段 0 的工程没有任何身份概念：任何能访问 8081 的人都能调所有接口。要变成"云端工作台"，第一件事就是回答"这个请求是谁发的"。

但这个项目的原始形态是**单机无账号**的（数据全在浏览器 IndexedDB），所以账号体系不是"迁移一个已有模块"，而是**新增一层**。这意味着设计自由度大，但也要避免过度设计。

约束条件：

1. 前端暂不改动 → 接口要自成体系，能被后续前端平滑接入；
2. 学习/简历用途 → 技术选型要有讨论价值，不能"够用就行"；
3. 单体服务 → 不引入网关、不引入统一认证中心。

## 二、整体架构

```
                    ┌──────────────────────────────────────┐
   请求 ──────────► │ TraceIdFilter          （分配 traceId）│
                    ├──────────────────────────────────────┤
                    │ Security FilterChain                 │
                    │  1. CorsFilter          （预检直接返回）│
                    │  2. JwtAuthenticationFilter          │
                    │       ├─ 取 Bearer token             │
                    │       ├─ 验签 / issuer / exp         │
                    │       ├─ 校验 type=access            │
                    │       ├─ 查 Redis 黑名单              │
                    │       └─ 写 SecurityContext          │
                    │  3. AuthorizationFilter （路径级授权） │
                    │       ├─ 未认证 → EntryPoint (401)   │
                    │       └─ 无权限 → AccessDenied (403) │
                    ├──────────────────────────────────────┤
                    │ DispatcherServlet → Controller → ... │
                    └──────────────────────────────────────┘

         ┌────────────┐        ┌────────────┐        ┌────────────┐
         │   MySQL    │        │   Redis    │        │  内存/配置  │
         │  t_user    │        │ 白名单/黑名单 │        │ JWT 密钥    │
         └────────────┘        └────────────┘        └────────────┘
```

**MySQL** 存用户事实（唯一身份）；**Redis** 存"令牌状态"（可变的、需要过期的）；**密钥**在配置里（不变的）。

这个划分标准是：**数据是否具有"短期可变性"**。用户资料会长期存在，放 MySQL；令牌是否有效每秒钟都可能变，放 Redis。

## 三、分层职责

| 层 | 类 | 职责 | 不该做的事 |
| --- | --- | --- | --- |
| 接入层 | `JwtAuthenticationFilter` | 把 token 变成 `LoginUser` | 查用户资料、判断业务权限 |
| 接入层 | `RestAuthenticationEntryPoint` | 输出 401 JSON | 决定"为什么 401"（那是过滤器的事） |
| 接口层 | `AuthController` / `UserController` | 接参、校验、调 Service | 写业务逻辑 |
| 业务层 | `AuthServiceImpl` | 注册登录流程编排、令牌签发 | 拼 SQL、写 HTTP 相关代码 |
| 业务层 | `UserServiceImpl` | 资料读写 | 处理认证 |
| 存储层 | `UserMapper` | 数据访问 | 业务判断 |
| 状态层 | `TokenStore` / `LoginAttemptGuard` | Redis 读写，封装 key 规则 | 决定"什么时候该撤销" |

一条重要的边界：**`TokenStore` 不判断业务**。它只提供 `saveRefreshToken` / `revokeRefreshToken` / `blacklistAccessToken` 这些原子操作，"什么时候调用"由 `AuthServiceImpl` 决定。这样以后想换存储（比如换成数据库表）只改一个类。

## 四、核心流程

### 登录

```
POST /v1/auth/login {account, password}
  │
  ├─ LoginAttemptGuard.lockedSeconds(account)      Redis GET + TTL
  │     └─ > 0 → 429 ACCOUNT_LOCKED
  │
  ├─ UserMapper.selectOne(username = ? OR email = ?)   MySQL
  │
  ├─ passwordEncoder.matches(raw, hash ?: dummyHash)   CPU 约 60ms
  │     └─ 失败 → recordFailure() → 401 BAD_CREDENTIALS（附剩余次数）
  │
  ├─ user.active()? 否 → 401 ACCOUNT_DISABLED
  │
  ├─ clear(account)                                   Redis DEL
  ├─ UPDATE last_login_at / last_login_ip             MySQL
  │
  └─ issueTokens()
        ├─ JwtService.issueAccessToken   （30 分钟）
        ├─ JwtService.issueRefreshToken  （14 天）
        └─ TokenStore.saveRefreshToken   （Redis SET + TTL 14 天）
  │
  └─ 200 {accessToken, refreshToken, expiresIn, user}
```

**顺序的两个关键点**：
1. 锁定检查在 BCrypt 之前（省 CPU，防资源耗尽）；
2. 清计数在密码校验之后（否则锁定失效）。

### 刷新

```
POST /v1/auth/refresh {refreshToken}
  │
  ├─ 验签 + issuer + exp          签名/过期 → 40112
  ├─ type == refresh?             否 → 40112
  ├─ Redis 白名单有该 jti?         否 → 40114（可能是重放）
  ├─ 查用户 + 状态检查             禁用 → 40111（顺手撤销）
  ├─ revoke 旧 jti                 Redis DEL
  └─ 签发新的一对                   轮换完成
```

**为什么一定要轮换**：refresh 有效期 14 天，如果不轮换，一个泄露的 refresh 能被用满 14 天。轮换后它只能被用一次，第二次使用必然失败——失败本身就是入侵信号。

### 认证过滤器（每个受保护请求）

```
请求 → 取 Authorization
  ├─ 无 → 放行（交给授权阶段判 401）
  ├─ 验签失败 / 格式错 → 挂 TOKEN_INVALID，放行
  ├─ 已过期 → 挂 TOKEN_EXPIRED，放行
  ├─ type != access → 挂 TOKEN_INVALID，放行
  ├─ 黑名单命中 → 挂 TOKEN_REVOKED，放行
  └─ 通过 → 构造 LoginUser → SecurityContext → 放行
```

**"失败也放行"是整个设计里最反直觉的一点**，理由见 `tech/03`：放行路径（登录接口）带着过期 token 也要能访问；且拒绝必须由统一出口输出 JSON。

## 五、错误码设计

在阶段 0 的基础上扩展，保持"五位数、前三位是 HTTP 状态"的规则：

| 码 | 常量 | HTTP | 场景 | 前端建议动作 |
| --- | --- | --- | --- | --- |
| 0 | SUCCESS | 200 | 成功 | — |
| 40000 | BAD_REQUEST | 400 | 参数校验失败 | 展示 message |
| 40100 | UNAUTHORIZED | 401 | 没带 token | 跳登录页 |
| **40110** | BAD_CREDENTIALS | 401 | 账号或密码错 | 展示剩余次数 |
| **40111** | ACCOUNT_DISABLED | 401 | 账号被禁用 | 提示联系管理员 |
| **40112** | TOKEN_INVALID | 401 | 签名错/格式错/类型错 | 清本地 token，跳登录 |
| **40113** | TOKEN_EXPIRED | 401 | access 过期 | **先尝试静默刷新** |
| **40114** | TOKEN_REVOKED | 401 | 已登出/已轮换 | 清本地 token，跳登录 |
| 40300 | FORBIDDEN | 403 | 已登录但无权限 | 提示无权限 |
| 40400 | NOT_FOUND | 404 | 资源不存在 | — |
| **40910** | USERNAME_TAKEN | 409 | 用户名占用 | 提示换一个 |
| **40911** | EMAIL_TAKEN | 409 | 邮箱占用 | 提示换一个 |
| **42910** | ACCOUNT_LOCKED | 429 | 登录失败过多 | 提示等待 |

**为什么 401 要分五种**：前端行为完全不同。40113 应该"自动刷新然后重试原请求"，40112/40114 应该"清态跳登录"。如果统一成 401，前端只能一律跳登录，用户体验会明显变差（30 分钟就被踢一次）。

## 六、设计决策记录

| 编号 | 决策 | 备选 | 理由 | 代价 |
| --- | --- | --- | --- | --- |
| D-01 | JWT 无状态认证 | Session + Cookie | 多实例免共享；前端（未来可能部署到 CDN）好接 | 无法立即踢人，需要 Redis 补 |
| D-02 | Access 30min + Refresh 14d | 单个长有效期 token | 泄露窗口小；刷新对用户无感 | 前端要写刷新逻辑 |
| D-03 | Refresh 白名单 | 纯黑名单 | 能批量失效、能做轮换检测 | 每次登录写一次 Redis |
| D-04 | Access 黑名单 | 不做撤销 | 登出立即生效 | 每请求一次 Redis 查询 |
| D-05 | 显式指定 HS256 | 让 jjwt 按密钥长度自动选 | 行为可预期（实测自动选了 HS384） | 无 |
| D-06 | 失败计数按账号 | 按 IP / 组合 | 不误伤 NAT 后的整个网络 | 可被用来恶意锁定他人 |
| D-07 | 时序防护用 dummyHash | 不处理 | 防账号枚举 | 每次失败多 60ms |
| D-08 | 不用 `UserDetailsService` | 实现它 + `AuthenticationProvider` | 登录逻辑简单直白，不必迁就框架的抽象 | 放弃了 Security 生态里的部分组件（如 `DaoAuthenticationProvider` 的自动锁定） |
| D-09 | 资源路径用 `/me` | `/users/{id}` | 结构上消除越权 | 管理员操作他人需要单独接口 |
| D-10 | 建表用 `spring.sql.init` | Flyway / Liquibase | 现阶段只有一张表，不值得引入迁移框架 | 改表结构需要手动 `ALTER` |

### D-08 展开：为什么不用 `UserDetailsService`

Spring Security 的标准套路是：实现 `UserDetailsService#loadUserByUsername` → 配 `DaoAuthenticationProvider` → 用 `AuthenticationManager.authenticate()`。

没走这条路的原因：

1. 它的抽象是为**表单登录**设计的，`loadUserByUsername` 只能按一个字段查（用户名或邮箱二选一就要写死）；
2. 失败计数、时序防护、禁用状态判断都要塞进 `UserDetails` 的 `isEnabled/isAccountNonLocked` 里，反而绕；
3. 我们的场景不需要 `AuthenticationManager` 的多 provider 链。

代价是：Spring Security 的一些开箱能力（比如 `AccountStatusException` 的自动翻译）用不到。这些在本项目里都是手写的，代码更直白。

**注意**：正因为不用它，才要 `exclude = UserDetailsServiceAutoConfiguration`，否则 Boot 会创建一个带随机密码的内存用户，并在启动日志里打印一段容易误导人的 `Using generated security password`。

## 七、Redis Key 设计

| Key | Value | TTL | 用途 |
| --- | --- | --- | --- |
| `auth:refresh:{jti}` | userId | 14 天 | refresh token 白名单 |
| `auth:blacklist:{jti}` | `1` | 该 token 剩余寿命 | access token 黑名单 |
| `auth:login:fail:{account小写}` | 失败次数 | 15 分钟（首次失败时设） | 登录失败计数 |

命名规范：`业务域:子类型:标识`。好处是 `SCAN auth:refresh:*` 能扫出所有会话（后续做"会话管理"页面就靠它），也方便按前缀清理测试数据。

**为什么不用 hash 结构**（比如一个 `auth:refresh` hash 存所有 jti）：Hash 的 field 没法单独设 TTL（Redis 7 之前），而令牌必须能独立过期。所以用独立 key。

## 八、安全考量清单

| 威胁 | 防御 | 位置 |
| --- | --- | --- |
| 密码爆破 | 失败计数 + 锁定 | `LoginAttemptGuard` |
| 账号枚举（文案） | 统一错误文案 | `AuthServiceImpl#login` |
| 账号枚举（时序） | dummyHash 等时校验 | `AuthServiceImpl#login` |
| 密码泄露（拖库） | BCrypt + 随机盐 | `SecurityConfig` |
| Token 伪造 | HMAC 签名 + issuer 校验 | `JwtService` |
| Token 重放（refresh） | 白名单 + 轮换 | `TokenStore` |
| 登出后继续使用 | access 黑名单 | `TokenStore` |
| 越权改他人资料 | `/me` 路径 | `UserController` |
| XSS 通过头像地址 | `@Pattern` 限制协议 | `UpdateProfileRequest` |
| 信息泄露（响应体） | VO 不含 `passwordHash` | `UserVO` |
| 敏感信息进日志 | 只打 userId / username，不打 token / 密码 | 各 Service |

**未覆盖的威胁**（明确记录，不假装没有）：
- 登录接口无速率限制（可以换账号轮着试）→ 阶段 8 用 AOP + Redis 做接口级限流；
- 无验证码 → 生产环境建议高风险时启用；
- 无 HTTPS → 阶段 8 由 Nginx 终结 TLS；
- 无 token 设备绑定 → refresh token 被盗后无法识别异常设备。

## 九、与前后端对接的约定

1. **access token 放 `Authorization: Bearer xxx`**，不放 URL（会进日志）。
2. **refresh token 的存放位置**：现阶段建议内存 + `localStorage`；更安全的是 HttpOnly Cookie（能防 XSS 读取），但需要处理 CSRF，阶段 9 再权衡。
3. **前端刷新策略**：`accessExpiresIn` 倒计时，提前 60 秒静默刷新；或收到 40113 时刷新并重放一次原请求（要防重放风暴：同一时刻只允许一个刷新请求在飞）。
4. **null 字段会被省略**（全局 `non_null`），前端取值统一用 `??` 兜底。
5. **所有出参 id 都是字符串**（雪花 ID 精度），前端不要再 `parseInt`。

## 十、已知取舍

| 项 | 取舍 | 影响 |
| --- | --- | --- |
| 用户状态变更延迟 | access token 内不校验用户状态 | 禁用用户后最多 30 分钟内仍可访问 |
| 修改密码不踢人 | 未实现"全部会话失效" | 改密码后旧 refresh 仍可用 |
| 锁定可按账号 DoS | 选择了账号维度 | 恶意者能锁他人账号 15 分钟 |
| 无角色体系 | 权限恒为 `ROLE_USER` | 无法做管理后台的分级授权 |
| 单设备登出 | 只能撤销当前设备的 refresh | 其他设备仍在线 |

这五项都是**有意识的放弃**，不是遗漏。面试被问到"你觉得这个设计有什么问题"时，直接说这五条比硬找理由强。
