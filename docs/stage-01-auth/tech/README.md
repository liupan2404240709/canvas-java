# 阶段 1 技术说明 · 目录

阶段 1 一共 12 篇逐模块说明，按「从外到内」的顺序排列：先讲请求怎么被放进来（Security 过滤链），再讲凭证怎么签发和校验，最后讲业务怎么落库。

| 序号 | 文档 | 覆盖的源码 | 一句话 |
| --- | --- | --- | --- |
| 01 | [Spring Security 过滤链](./01-SpringSecurity过滤链.md) | `SecurityConfig` | 谁在什么时候决定"这个请求要不要登录" |
| 02 | [JWT 签发与校验](./02-JWT签发与校验.md) | `JwtService` `JwtProperties` `IssuedToken` | 令牌长什么样、怎么签名、怎么验 |
| 03 | [JWT 认证过滤器](./03-JWT认证过滤器.md) | `JwtAuthenticationFilter` `LoginUser` `SecurityUtils` | 把 Header 里的字符串变成"当前用户" |
| 04 | [认证失败统一响应](./04-认证失败统一响应.md) | `RestAuthenticationEntryPoint` `RestAccessDeniedHandler` | 401 / 403 也要走统一响应体 |
| 05 | [BCrypt 密码存储](./05-BCrypt密码存储.md) | `SecurityConfig#passwordEncoder` `AuthServiceImpl#dummyHash` | 密码为什么不能加密只能哈希 |
| 06 | [Refresh Token 白名单与轮换](./06-RefreshToken白名单与轮换.md) | `TokenStore` | JWT 无法撤销，怎么补上 |
| 07 | [登录失败锁定](./07-登录失败锁定.md) | `LoginAttemptGuard` `LoginProtectionProperties` | 防爆破、防用户枚举 |
| 08 | [用户实体与 Mapper](./08-用户实体与Mapper.md) | `User` `UserMapper` `@MapperScan` | MyBatis-Plus 怎么把类映射成表 |
| 09 | [注册登录业务编排](./09-注册登录业务编排.md) | `AuthService` `AuthServiceImpl` | 一次登录到底发生了什么 |
| 10 | [参数校验与 DTO](./10-参数校验与DTO.md) | `dto/*` | 为什么入参不用 Entity |
| 11 | [表结构设计](./11-表结构设计.md) | `db/schema.sql` | 每个字段和索引为什么长这样 |
| 12 | [越权防护与资料接口](./12-越权防护与资料接口.md) | `UserController` `UserServiceImpl` | 为什么 URL 里不能出现别人的 id |

## 面试话术总纲

如果只准备一段话，背这一段（覆盖 01~07 的核心）：

> 这个项目的鉴权是 **JWT 无状态 + Redis 补状态** 的组合。请求进来先过一个 `OncePerRequestFilter`，从 `Authorization: Bearer` 里取 token，用 HS256 验签并校验 issuer 和过期时间，通过就把一个轻量的 `LoginUser` 放进 `SecurityContext`，后续授权只看 `SecurityContext`，不再查库。
>
> 但 JWT 天生不能撤销，所以我做了两件事：**refresh token 用 Redis 白名单**，只有白名单里的 jti 才能换新令牌，登出或轮换时删掉即可；**access token 用 Redis 黑名单**，登出时按剩余有效期写入，TTL 到期自动清理，不会无限增长。
>
> 另外登录做了两个防护：同一账号连续失败 5 次锁 15 分钟，计数放 Redis 所以重启不清零；账号不存在时也走一次 BCrypt 校验并返回同样的错误文案，避免通过响应时间或提示文案枚举出哪些账号真实存在。

追问时的补充点：

- 为什么不用 Session：无状态部署不用做 session 共享，代价是无法"立即"踢人，所以才有黑名单。
- 为什么 access 30 分钟：越短越安全，越长越省刷新；30 分钟配合前端提前 60 秒静默刷新是常见折中。
- 为什么 refresh 要轮换：旧 token 只能用一次，泄露后一旦被用就会因为白名单里没有而失败，能暴露异常。
- 权限模型：阶段 1 只有"登录 / 未登录"，角色表留给阶段 2 之后；`RestAccessDeniedHandler` 已经提前写好。

## 阅读顺序建议

- 想搞懂"为什么我的接口莫名其妙 401"：直接看 01。
- 想搞懂"前端存 token 有什么讲究"：看 02、06。
- 面试前一天：看每篇末尾的**自检清单**，答不上来的回去补对应小节。
