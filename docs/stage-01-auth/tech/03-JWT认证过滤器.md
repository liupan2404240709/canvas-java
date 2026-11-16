# 03 · JWT 认证过滤器

## 一句话定位

`JwtAuthenticationFilter` 是"字符串 → 当前用户"的唯一转换点：它把 `Authorization: Bearer xxx` 变成一个放进了 `SecurityContext` 的 `LoginUser`。

## 完整代码

```java
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    public static final String ATTR_AUTH_ERROR = JwtAuthenticationFilter.class.getName() + ".error";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final TokenStore tokenStore;
    // 构造注入省略

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = resolveToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwtService.parse(token);
            if (!JwtService.TYPE_ACCESS.equals(jwtService.typeOf(claims))) {
                request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_INVALID);
            } else if (tokenStore.isAccessTokenBlacklisted(claims.getId())) {
                request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_REVOKED);
            } else {
                LoginUser loginUser = new LoginUser(
                        jwtService.userIdOf(claims),
                        jwtService.usernameOf(claims),
                        claims.getId(),
                        claims.getExpiration().toInstant());

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        loginUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (ExpiredJwtException e) {
            request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("token 解析失败: {}", e.getMessage());
            request.setAttribute(ATTR_AUTH_ERROR, ResultCode.TOKEN_INVALID);
        }

        chain.doFilter(request, response);
    }
}
```

## 逐段拆解

### ① 为什么继承 `OncePerRequestFilter`

Servlet 规范里，一个请求可能因为 **forward**（比如异常转发到 `/error`）或 **include** 多次穿过同一条过滤器链。如果过滤器没有幂等保护，就会重复执行认证逻辑。

`OncePerRequestFilter` 的做法：用 `request.setAttribute(alreadyFilteredAttributeName, TRUE)` 打标，第二次进入时直接 `chain.doFilter` 放行。

**不继承会怎样**：`DispatcherType.FORWARD` 时重复解析 token；如果第一次解析失败挂了错误属性，第二次可能覆盖成成功状态，行为变得不可预测。

### ② 核心设计：失败**不拦截**，只挂原因

这是整个类最重要的决定。看代码：所有失败分支都是 `request.setAttribute(ATTR_AUTH_ERROR, 某个 ResultCode)`，然后**继续 `chain.doFilter`**。

理由有两个：

1. **放行路径也会带 token**。前端在本站所有请求上统一挂 `Authorization` 头，token 过期后用户点"登录"，请求里可能还带着那个过期 token。如果在过滤器里直接返回 401，会出现"token 过期后连登录接口都调不了"的死锁。
2. **拒绝要统一出口**。401 的响应体必须和项目其他错误一样是 `{code, message, traceId}`。过滤器直接写响应会绕过 `ObjectMapper` 的配置（时间格式、字段命名），也会让 401 有两套格式。所以拒绝交给后面的授权阶段，由 `RestAuthenticationEntryPoint` 统一输出（见 04 篇）。

**不这么写会怎样**：你会遇到"过期 token 导致无法重新登录"，以及 401 响应体和其他接口不一致，前端拦截器判断分支要写两套。

### ③ `type` 校验必须在最前面

```java
if (!JwtService.TYPE_ACCESS.equals(jwtService.typeOf(claims))) { ... }
```

access 和 refresh 用同一把密钥签名，验签通过不代表"这是 access token"。不判断的话：
- 用户拿 refresh token 当 access token 调业务接口 → 一个 14 天有效的令牌可以访问所有业务接口，等于 access token 的 30 分钟限制失效。

### ④ 黑名单检查的位置

放在 `type` 校验之后、构造 `LoginUser` 之前。顺序有讲究：`type` 不对的 token 不该去查 Redis（省一次网络往返）。

**性能考虑**：每个带 token 的请求都会多一次 Redis `hasKey`。单机 Redis 约 0.1~0.3ms，可接受。如果要进一步优化，可以把"未拉黑"的 jti 在本地 Caffeine 缓存几秒——但会延长撤销生效延迟，阶段 1 不做。

### ⑤ `LoginUser` 为什么是 record 而不是 Entity

| 方案 | 问题 |
| --- | --- |
| 放 `User` 实体 | 带着 `passwordHash` 在 `SecurityContext` 里流转，任何一处日志打印或序列化都可能泄露 |
| 放 `username` 字符串 | 拿不到 id，业务层还得再查一次库 |
| 放 `LoginUser` record | 只有 id / username / jti / 过期时间，够用且安全 |

`tokenId` 和 `tokenExpiresAt` 是给登出用的：登出时要把**当前这一串** access token 拉黑，需要 jti 和它的剩余寿命。

### ⑥ `UsernamePasswordAuthenticationToken` 三个参数的含义

```java
new UsernamePasswordAuthenticationToken(principal, credentials, authorities)
```

- 用**三参构造**：表示"已认证"（`isAuthenticated() == true`）。两参构造表示"待认证"，`AuthorizationFilter` 会认为你没登录，这是极易踩的坑。
- `credentials` 传 `null`：密码在 JWT 场景里根本不需要留在内存中。
- `authorities` 至少给一个 `ROLE_USER`：`.authenticated()` 只要求"有认证对象"，但后续如果加了 `hasRole('USER')`，没有权限列表就会 403。

### ⑦ `setDetails(...)` 有什么用

把 `WebAuthenticationDetails`（含 remoteAddress、sessionId）挂到认证对象上。业务代码目前用不到，但排障和审计（"这个请求从哪个 IP 来的"）时会用到，成本是零。

### ⑧ `resolveToken` 为什么判断得这么啰嗦

```java
if (!StringUtils.hasText(header) || !header.startsWith(PREFIX)) return null;
String token = header.substring(PREFIX.length()).trim();
return token.isEmpty() ? null : token;
```

- `hasText` 同时挡住 `null`、`""`、`"   "`；
- `startsWith("Bearer ")` 严格匹配大小写。HTTP 里 scheme 是大小写不敏感的，理论上 `bearer` 也合法；这里选择严格是为了**统一前端行为**，避免不同客户端写法不一致带来的排查成本。如果要兼容，改成 `regionMatches(true, 0, PREFIX, 0, PREFIX.length())`。
- `substring` 之后 `trim`：防止 `Bearer  xxx`（两个空格）解析出带空格的 token。

## 设计决策对比

| 决策 | 选的 | 替代 | 代价 |
| --- | --- | --- | --- |
| 失败处理 | 挂属性继续走 | 直接返回 401 | 换来统一响应格式，代价是逻辑稍绕 |
| 用户信息来源 | 只信 token | 每个请求查库校验用户状态 | 省一次 DB 查询，代价是禁用用户后 30 分钟内仍可访问 |
| 权限 | 固定 `ROLE_USER` | 查角色表 | 阶段 1 没有角色概念，查了也用不上 |
| 黑名单检查 | 每请求查 Redis | 不查（纯 JWT） | 换来"登出立即生效"，代价是每请求一次 Redis |

## 面试考察点

**基础**

- `OncePerRequestFilter` 和 `Filter` 的区别？—— 前者解决 forward/include 导致重复执行的问题。
- `Filter`、`Interceptor`、`AOP` 的执行顺序？—— Filter（Servlet 容器）→ Interceptor（Spring MVC）→ AOP（Spring 代理）。
- 认证信息放在哪？—— `SecurityContextHolder`，默认 `ThreadLocal` 策略。

**进阶**

- 为什么过滤器里不直接 `response.getWriter().write(...)` 返回 401？—— 绕过统一响应封装、可能重复写响应、和 `ExceptionTranslationFilter` 的职责重叠。
- 为什么 `UsernamePasswordAuthenticationToken` 要用三参构造？—— 两参构造 `isAuthenticated=false`，授权阶段视为未登录。
- 拦截器（Interceptor）能不能替代这个过滤器？—— 能认证，但 `AuthorizationFilter` 在 MVC 之前执行，用拦截器做认证会导致授权永远判定未登录。
- 每个请求都查 Redis 会不会成瓶颈？—— 量级评估：单机 Redis 10 万 QPS，业务接口本身有 DB 查询，0.2ms 的额外开销可接受；真要优化就本地缓存 + 短 TTL。

**陷阱**

- "token 正确但还是 401"—— 过滤器没加到链里，或加在了 `AuthorizationFilter` 之后。
- "登出后 token 还能用"—— 没做黑名单，或黑名单 key 用了原始 token 而不是 jti（原始 token 太长且含 `.`）。
- "异步任务里 `SecurityUtils.currentUser()` 抛 401"—— ThreadLocal 不跨线程，需要手动传递（阶段 6 的伏笔）。

## 常见坑

1. **`chain.doFilter` 只能调用一次**。所有分支都要保证最终只走一次，否则响应会被写两遍。
2. **异常捕获要区分 `ExpiredJwtException` 和 `JwtException`**。前者是后者的子类，顺序反了会永远拿到"凭证无效"而不是"已过期"，前端就没法触发自动刷新。
3. **`claims.getExpiration()` 可能为 null**（如果签发时没设 `exp`）。我们签发时一定设了，但防御性写法可以加判空。
4. **不要在这里 `throw` 业务异常**。过滤器阶段抛出的异常走不到 `@RestControllerAdvice`，最终会变成 Tomcat 的默认 500 页面。

## 自检清单

- [ ] 能解释为什么认证失败不直接返回 401
- [ ] 知道 `OncePerRequestFilter` 解决的具体问题（forward）
- [ ] 能说出 `SecurityContext` 的存储位置和它的跨线程局限
- [ ] 知道两参 / 三参 `UsernamePasswordAuthenticationToken` 的区别
- [ ] 能解释为什么必须先校验 `type`
- [ ] 能说出黑名单检查放在哪个位置、为什么放在 `type` 之后
- [ ] 能解释 `LoginUser` 为什么不直接用 Entity
