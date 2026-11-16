# 01 · Spring Security 过滤链

## 一句话定位

`SecurityConfig` 回答了三个问题：**哪些路径不用登录**、**没登录时返回什么**、**认证发生在过滤链的哪一环**。

## 完整代码

```java
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, LoginProtectionProperties.class})
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            ApiPath.AUTH + "/register",
            ApiPath.AUTH + "/login",
            ApiPath.AUTH + "/refresh",
            ApiPath.V1 + "/health",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

## 逐段拆解

### ① `@EnableWebSecurity` 到底做了什么

它不是"开启安全"的开关——只要引入 `spring-boot-starter-security`，安全就自动生效了。它真正的作用是导入 `WebSecurityConfiguration`，让容器里出现 `FilterChainProxy` 这个 Bean。

**不写会怎样**：`SecurityFilterChain` 这个 Bean 仍然会被创建（Boot 的 `SecurityAutoConfiguration` 会兜底配一个默认链），但默认链是"所有请求都要认证 + 表单登录"，表现就是：接口全部 401、访问任何路径都跳转到一个登录页。

### ② 为什么关掉 csrf / formLogin / httpBasic / logout

| 关掉的东西 | 理由 | 不关的后果 |
| --- | --- | --- |
| `csrf` | CSRF 利用的是浏览器**自动携带 Cookie**；我们用 `Authorization` 头，浏览器不会自动加 | 所有 POST/PUT/DELETE 都要额外带 csrf token，前端直接 403 |
| `formLogin` | 它会注册 `/login` 页面和 `UsernamePasswordAuthenticationFilter` 的表单流程 | 未登录时返回 302 跳转 HTML 页面，前端拿到的是登录页源码 |
| `httpBasic` | 弹浏览器原生账号密码框 | 前端 401 时可能弹出原生对话框，体验很差 |
| `logout` | 默认 `/logout` 端点会清 session 并 302 | 和我们自己的 `POST /v1/auth/logout` 语义冲突，且它会尝试写 session |

注意 `logout` 这个点：默认的 `LogoutFilter` 匹配的是 `/logout`，它和 `/v1/auth/logout` 不冲突，但它依赖于"清 session"这套逻辑，在 STATELESS 模式下没有意义，留着只会让人困惑。

### ③ `sessionCreationPolicy(STATELESS)` 的准确含义

它不是说"完全不创建 session 对象"，而是**不让 Security 通过 session 保存 `SecurityContext`**。原来 `SecurityContextPersistenceFilter` 会把认证信息写进 `HttpSession`，现在换成 `SecurityContextHolderFilter`，只在一次请求内有效。

**不写会怎样**：第一次请求认证后 Tomcat 会下发 `JSESSIONID`，多实例部署时 A 实例认的 token 到 B 实例虽然也能验签通过（JWT 优势还在），但 session 里的 CSRF token、并发会话控制这些能力会互相打架，还会白白占用内存。

### ④ `PUBLIC_PATHS` 里为什么**没有** `/api` 前缀

这是接 Security 时最高频的 401 来源。

`server.servlet.context-path: /api` 是**容器层**的概念：Tomcat 把 `/api/v1/auth/login` 里的 `/api` 剥掉，剩下的 `/v1/auth/login` 才是 `HttpServletRequest.getServletPath()`。而 Security 的 `requestMatchers` 匹配的正是 servletPath。

所以：

| 写错的方式 | 结果 |
| --- | --- |
| `requestMatchers("/api/v1/auth/login")` | 永远匹配不上，登录接口被 401 拦掉 |
| `requestMatchers("/v1/auth/login")` | 正确 |

### ⑤ `/error` 为什么必须放行

Spring Boot 处理异常时会把请求 **forward** 到 `/error`（`BasicErrorController`），这个 forward 会**再走一遍过滤链**。如果 `/error` 需要认证，那么任何匿名请求触发的 404 都会变成 401——排查时会以为"路径配错了"，其实是错误页被拦了。

### ⑥ `OPTIONS /**` 为什么单独放行

跨域预检请求由浏览器发起，**不会携带 `Authorization` 头**（规范如此，预检的目的就是问"我能不能带这个头"）。如果不放行，预检直接 401，浏览器控制台只会显示一句 `CORS error`，看不出是鉴权问题。

`cors(Customizer.withDefaults())` 会让 `CorsFilter` 在授权之前处理预检并直接返回 200，理论上轮不到授权判断。但显式放行是一层保险——万一 CORS 配置被改动、预检落到了授权阶段，也不至于挂。

### ⑦ `addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)` 的位置含义

Security 的过滤链是固定顺序的，简化后关键几环是：

```
SecurityContextHolderFilter → CorsFilter → LogoutFilter → JwtAuthenticationFilter
→ UsernamePasswordAuthenticationFilter → ... → AuthorizationFilter
```

我们的 JWT 过滤器必须排在 `AuthorizationFilter`（做授权判断的那个）**之前**，否则到了授权环节 `SecurityContext` 还是空的，所有请求都会被判定为未认证。

选 `UsernamePasswordAuthenticationFilter` 作为锚点，是因为它代表"传统表单认证发生的位置"——我们替换掉的正是这一步。

**不写会怎样**：过滤器压根不注册进链里，表现为"带了正确 token 依然 401"。

### ⑧ 为什么 CORS 直接 `withDefaults()`

`WebMvcConfig` 里已经有 `addCorsMappings` 的全集配置，Security 的 `CorsConfigurer` 在找不到 `CorsConfigurationSource` Bean 时，会退而使用 `HandlerMappingIntrospector` 去读 MVC 层的 CORS 规则。

好处是不用在两处维护同一份 `allowed-origins`。代价是耦合了 MVC 的实现，所以实测过一遍（见阶段 README 的实测记录）：

```
Access-Control-Allow-Origin: http://localhost:5173
Access-Control-Allow-Credentials: true
```

## 设计决策对比

| 决策 | 选的方案 | 替代方案 | 代价 |
| --- | --- | --- | --- |
| 认证方式 | JWT 无状态 | Session + Cookie | Session 能立刻踢人，但要解决多实例共享 |
| 授权粒度 | 路径级（`anyRequest().authenticated()`） | 方法级 `@PreAuthorize` | 方法级更灵活，但阶段 1 还没有角色，属于提前设计 |
| 放行清单 | 常量数组集中管理 | 每个 Controller 上打注解 | 集中式一眼能看完，新增接口容易漏（所以写死了"必须同步"的注释） |
| CORS | 复用 MVC 配置 | 在 Security 里单独建 `CorsConfigurationSource` | 单独建更明确，但要维护两份 origin 列表 |

## 面试考察点

**基础**

- `@EnableWebSecurity` 的作用是什么？—— 导入 `WebSecurityConfiguration`，注册 `FilterChainProxy`；不是"开启安全"的开关。
- `SecurityFilterChain` 和 `FilterChainProxy` 的关系？—— 后者是 Servlet 过滤器，内部持有多个 `SecurityFilterChain`，按 `RequestMatcher` 选中第一个匹配的链执行（支持多套安全策略，比如 `/api/**` 和 `/admin/**` 用不同规则）。
- `permitAll()` / `authenticated()` / `hasRole()` 的区别？

**进阶**

- 为什么 `requestMatchers` 不带 context-path？—— 匹配的是 servletPath，context-path 在进入 DispatcherServlet 前已被剥掉。
- `STATELESS` 之后 `SecurityContext` 存在哪？—— 只存在 `ThreadLocal`（`SecurityContextHolder` 的默认策略 `MODE_THREADLOCAL`），请求结束由 `SecurityContextHolderFilter` 清理。**这直接导致了阶段 6 的坑：异步线程里取不到当前用户。**
- 过滤器顺序错了会怎样？—— 授权在 `AuthorizationFilter` 做，认证过滤器排到它后面就永远认证不上。
- 为什么 `/error` 要放行？—— 异常 forward 会二次进链。

**陷阱**

- "我加了 Security，Swagger 打不开了"—— 通常是没放行 `/v3/api-docs/**`，或者放行路径写成了带 `/api` 前缀的。
- "登录接口返回 401 而不是我的业务错误"—— 说明登录路径不在放行清单里，请求根本没进 Controller。
- "预检请求 401 / 前端报 CORS 错误"—— 没有 `cors()` 或没有放行 `OPTIONS`。
- "跨域带上 Cookie 就失败"—— `allowCredentials(true)` 时 `allowedOrigins` 不能是 `*`。

## 常见坑

1. `PUBLIC_PATHS` 用了 `String[]` 是必须的：`requestMatchers(String...)` 是可变参数重载，传 `List` 会走到另一个重载导致行为不一致。
2. 新增匿名接口（比如后续的"公开提示词列表"）必须同步加进 `PUBLIC_PATHS`，否则前端会拿到 401。
3. `@EnableConfigurationProperties` 把两个配置类注册成 Bean。如果只在配置类上写 `@ConfigurationProperties` 而不注册，`@Validated` 不生效、注入会失败。

## 自检清单

- [ ] 能说出 `@EnableWebSecurity` 具体导入了什么
- [ ] 能解释为什么匹配路径不带 `/api`
- [ ] 能说出 4 个被关掉的默认功能及各自原因
- [ ] 知道 `STATELESS` 下 `SecurityContext` 存在 ThreadLocal，并知道它的后果
- [ ] 能画出过滤链里 JWT 过滤器和 `AuthorizationFilter` 的先后关系
- [ ] 知道 `/error` 不放行会出现什么现象
- [ ] 能解释 `permitAll` 和 `anonymous` 的区别（后者仍会走 `AnonymousAuthenticationFilter`，`SecurityContext` 里有个匿名 principal）
