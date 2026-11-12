# 08 · CORS 跨域

> 对应源码：`config/WebMvcConfig.java`
> 配套配置：`application.yml` 的 `app.cors.allowed-origins`

## 一、一句话定位

让前端（`http://localhost:5173`）能调用后端（`http://127.0.0.1:8081`）。

这不是「后端安全配置」，而是**浏览器同源策略的合法例外声明**——真正拦截请求的是浏览器，不是服务器。

## 二、完整代码

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
```

```yaml
app:
  cors:
    allowed-origins: http://localhost:5173,http://localhost:4173
```

- `5173`：Vite 开发服务器默认端口
- `4173`：Vite `preview` 默认端口

## 三、逐段拆解

### 3.1 同源策略到底拦什么

**同源** = 协议 + 域名 + 端口 三者完全相同。

| 页面地址 | 请求地址 | 是否同源 | 原因 |
| --- | --- | --- | --- |
| `http://localhost:5173` | `http://localhost:5173/api` | ✅ | 完全一致 |
| `http://localhost:5173` | `http://localhost:8081/api` | ❌ | **端口不同** |
| `http://localhost:5173` | `https://localhost:5173/api` | ❌ | 协议不同 |
| `http://localhost:5173` | `http://127.0.0.1:5173/api` | ❌ | **域名不同**（`localhost` ≠ `127.0.0.1`） |

**关键认知**：同源策略是**浏览器的行为**，不是服务器的。用 `curl` 或者 Postman 请求，**根本不存在跨域问题**——这也是「为什么 Postman 能通、浏览器不行」的经典困惑来源。

**同源策略拦的是什么**：不是「请求发不出去」，而是「**响应读不到**」。请求实际发出去了，服务器也处理了，但浏览器看到响应头里没有正确的 CORS 允许信息，就**不把响应交给 JS**。

> 所以「跨域导致重复提交」这种事故是真实存在的：请求成功了，但前端读不到响应以为失败，重试了一次。

### 3.2 简单请求和预检请求

浏览器把跨域请求分两类，处理方式完全不同。

#### 简单请求（Simple Request）

**满足全部三个条件**才算简单：

| 条件 | 要求 |
| --- | --- |
| 方法 | `GET` / `HEAD` / `POST` 之一 |
| 请求头 | 只有安全头（`Accept`、`Accept-Language`、`Content-Language`、`Content-Type` 且值为三者之一） |
| `Content-Type` | `text/plain` / `multipart/form-data` / `application/x-www-form-urlencoded` |

**流程**：直接发请求，浏览器检查响应头里的 `Access-Control-Allow-Origin`，匹配就把响应交给 JS。

```
浏览器 ──GET──→ 服务器
浏览器 ←──200 + ACAO── 服务器   ← 浏览器检查 ACAO，通过则放行
```

#### 预检请求（Preflight）

**只要有一个条件不满足**，就要先发 `OPTIONS` 探路。

**本项目的情况**：前端用 axios 发 JSON，`Content-Type: application/json` —— **不在三个允许值里**，所以所有 POST/PUT/DELETE 都是**预检请求**。

```
① 浏览器 ──OPTIONS──→ 服务器
           Origin: http://localhost:5173
           Access-Control-Request-Method: POST
           Access-Control-Request-Headers: content-type,authorization

② 浏览器 ←──204 + 一堆 ACA* 头── 服务器

③ 浏览器 ──POST──→ 服务器（真正的请求）
```

**两个请求 = 两倍网络开销**。这就是 `maxAge` 存在的意义。

### 3.3 `.maxAge(3600)` 的作用

预检结果会被浏览器**缓存**，`maxAge` 是缓存时长（秒）。

```java
.maxAge(3600)   // 1 小时内，同样的预检请求不再发
```

**不配或配 0**：每次真实请求前都要发一次 `OPTIONS`，接口延迟直接翻倍。

**配太长（如 86400）的代价**：改 CORS 配置后，客户端要等缓存过期才生效，调试时很烦。

**1 小时的取舍**：开发期足够减少噪音，又不会让配置变更等太久。

### 3.4 `allowedOriginPatterns` 和 `allowedOrigins` 的关键区别

这是**最容易踩的坑**，也呼应了 `allowCredentials(true)`。

| 方法 | 支持通配符 | 能否与 `allowCredentials(true)` 共存 |
| --- | --- | --- |
| `allowedOrigins("*")` | `*` 表示全部 | ❌ **不能**，浏览器会拒绝 |
| `allowedOrigins("http://a.com")` | 不支持 `*` 通配 | ✅ 可以 |
| **`allowedOriginPatterns(...)`**（当前） | 支持 `*` 和通配模式 | ✅ **可以** |

**为什么 `*` 和 `credentials` 不能共存**：

如果服务器返回：

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Credentials: true
```

意味着「**任何网站**都可以带着用户的 Cookie 来请求你的接口」。这是严重的安全漏洞（CSRF 的前置条件），所以浏览器规范直接禁止这个组合，会报：

```
The value of the 'Access-Control-Allow-Origin' header in the response
must not be the wildcard '*' when the request's credentials mode is 'true'.
```

**`allowedOriginPatterns` 怎么解决的**：它会把匹配到的请求的 `Origin` **原样回写**到 `Access-Control-Allow-Origin` 头里，而不是返回 `*`。

```
请求头  Origin: http://localhost:5173
响应头  Access-Control-Allow-Origin: http://localhost:5173   ← 具体值，不是 *
```

这样既支持通配（配置成 `*` 或 `https://*.example.com`），又满足浏览器规范。

**本项目选择 `allowedOriginPatterns` 的原因**：虽然当前配置的是两个具体域名（不需要通配），但将来如果加 `--host` 让手机访问（`http://192.168.1.100:5173`），用 pattern 只需改配置（`http://192.168.*.*:5173`），不用改代码逻辑。

### 3.5 `exposedHeaders("X-Trace-Id")` —— 和 04 篇的联动

这个配置是为了让前端 JS 能读到 `X-Trace-Id` 响应头。

**默认行为**：跨域时，JS 只能读 6 个「安全响应头」：

```
Cache-Control、Content-Language、Content-Length、
Content-Type、Expires、Last-Modified
```

其他所有响应头，包括自定义的 `X-Trace-Id`，**JS 一律读不到**（`response.headers.get('X-Trace-Id')` 返回 `null`）。

**所以必须显式暴露**：

```java
.exposedHeaders("X-Trace-Id")
```

**这条配置的实际价值**：前端 axios 拦截器拿到 traceId，报错时可以展示给用户：

```javascript
axios.interceptors.response.use(
  res => res,
  err => {
    const traceId = err.response?.headers['x-trace-id']
    message.error(`请求失败${traceId ? `（问题编号：${traceId}）` : ''}`)
    return Promise.reject(err)
  }
)
```

用户截图带着问题编号，你直接 grep 日志——**这是 traceId 设计形成闭环的最后一环**。

**面试可以串起来讲**：Filter 生成 traceId → MDC 写日志 → 响应头回写 → CORS 暴露 → 前端展示 → 用户反馈 → 后端 grep。六个环节缺一个，这条链路就断了。

### 3.6 `allowCredentials(true)` 意味着什么

```java
.allowCredentials(true)
```

它允许浏览器在跨域请求里携带凭证：

- Cookie
- `Authorization` 头（**注意：这个不受 CORS 控制**，见下面）
- TLS 客户端证书

**本项目为什么需要**：阶段 1 引入 JWT 后，前端要在 `Authorization: Bearer xxx` 里带 token。

**这里有个必须澄清的误区**：`Authorization` 头**不属于**「凭证（credentials）」范畴，它只是一个普通请求头。真正需要 `allowCredentials` 的是 Cookie。

但前端 axios 如果设了 `withCredentials: true`，就必须服务端也开 `allowCredentials`。**本项目阶段 1 用 JWT 放 header，理论上不需要 `allowCredentials`**——不过目前开着也无害（配合 `allowedOriginPatterns`）。

**什么时候必须关掉**：如果哪天 `allowedOriginPatterns` 配成了 `*`（全放行），同时 `allowCredentials(true)`，就变成了「任何网站都能带用户 Cookie 请求你的接口」——这是 CSRF 的完美条件。**生产环境必须配具体域名。**

### 3.7 阶段 1 的大坑：CORS 和 Spring Security 的顺序

源码注释里已经埋了伏笔：

> 阶段 1 引入 Spring Security 后，CORS 需要同时挂到 Security 的过滤器链上，届时会迁到这里统一处理。

**为什么**：Spring Security 的过滤器链**在 `DispatcherServlet` 之前**执行。如果 Security 判定请求未认证，**直接返回 401，根本不会走到 MVC 层**——也就不会带上 `WebMvcConfig` 配的 CORS 响应头。

**现象**：

```
浏览器控制台：CORS policy: No 'Access-Control-Allow-Origin' header is present
网络面板：OPTIONS 请求返回 401
```

看起来像「跨域配置错了」，实际是「Security 拦住了预检请求」。

**正确做法**（阶段 1 会这样写）：

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))  // ← 关键
        .csrf(AbstractHttpConfigurer::disable)
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/v1/auth/**", "/v1/health").permitAll()
            .anyRequest().authenticated())
        ...
}

@Bean
public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(List.of(allowedOrigins));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setExposedHeaders(List.of("X-Trace-Id"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
}
```

**还要注意**：`OPTIONS` 预检请求必须放行，不能被认证拦截：

```java
.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
```

**面试高频题**：「加了 Spring Security 之后跨域失效了，怎么回事？」——标准答案就是上面这套。

### 3.8 生产环境不需要 CORS

如果前端打包后用 Nginx 托管，并且 Nginx 把 `/api` 反向代理到后端：

```nginx
server {
    listen 80;
    root /usr/share/nginx/html;

    location /api/ {
        proxy_pass http://canvas-server:8081/api/;
    }
}
```

**从浏览器视角**：请求的是 `http://yourdomain.com/api/v1/health`，和页面同源（都是 `yourdomain.com:80`）。

**跨域消失了**——因为浏览器看到的是同源请求。请求到 Nginx 后，Nginx 到后端是服务器之间的调用，不受同源策略约束。

**所以 `app.cors.allowed-origins` 在生产环境应该配成空**，或者只保留一个开发用域名。这也是为什么它放在 `app:` 自定义配置下，而不是硬编码——阶段 8 部署时改配置即可。

## 四、设计决策

| 决策 | 选择 | 理由 | 替代方案与代价 |
| --- | --- | --- | --- |
| CORS 实现层 | `WebMvcConfigurer` | MVC 层声明，简单 | 自己写 Filter——可控但重复造轮子 |
| 域名匹配 | `allowedOriginPatterns` | 可与 `allowCredentials` 共存、支持通配 | `allowedOrigins("*")`——浏览器拒绝 |
| 凭证 | `allowCredentials(true)` | 将来可能要带 Cookie | `false`——JWT in header 场景其实可以关 |
| 暴露头 | `X-Trace-Id` | 前端要展示 traceId | 不配——前端读不到 |
| 预检缓存 | `maxAge(3600)` | 减少 OPTIONS 请求 | 不配——每次请求翻倍 |
| 白名单来源 | **配置文件** | 部署时可按环境改 | 硬编码——改环境要改代码 |
| 生产环境 | 由 Nginx 同源代理 | 彻底消除跨域 | 一直开 CORS——多一层攻击面 |

## 五、面试考察点

### 基础题

**Q1：什么是同源策略？跨域是谁拦的？**

答题要点：
- 同源 = 协议 + 域名 + 端口全相同
- 是**浏览器**的行为，不是服务器。`curl` / Postman 不受影响
- 拦的不是「请求发不出去」，而是「**响应读不到**」
- 所以会出现「请求成功了但前端报错」

**Q2：简单请求和预检请求的区别？**

答题要点：
- 简单请求：`GET/HEAD/POST` + 安全头 + `Content-Type` 是三种之一，直接发
- 预检：不满足上述条件，先发 `OPTIONS`，服务器返回允许的方法和头，再发真实请求
- 本项目用 `application/json`，**所有 POST 都是预检**
- `maxAge` 用来缓存预检结果

**Q3：`allowedOrigins` 和 `allowedOriginPatterns` 有什么区别？**

答题要点：
- `allowedOrigins` 只支持精确匹配或 `*`，但 `*` 不能和 `allowCredentials(true)` 共存
- `allowedOriginPatterns` 支持通配符，且会把匹配到的 Origin **原样回写**而不是返回 `*`
- 所以需要 credentials 或通配时必须用 pattern

**Q4：为什么 `Access-Control-Allow-Origin: *` 不能配 `Allow-Credentials: true`？**

答题要点：
- 意味着任何网站都能带用户 Cookie 请求你的接口，是 CSRF 的完美条件
- 浏览器规范明确禁止这个组合，会直接拒绝
- 解法：用 pattern 回写具体 Origin

### 进阶题

**Q5：加了 Spring Security 后跨域失效，为什么？**

答题要点（**高频**）：
- Security 过滤器链在 `DispatcherServlet` 之前，`WebMvcConfig` 的 CORS 在 MVC 层
- Security 拦住请求（如返回 401）时，MVC 层根本没执行，CORS 头没加上
- 解决：`http.cors(...)` 挂 `CorsConfigurationSource`，让 Security 自己处理 CORS
- 还要注意放行 `OPTIONS` 预检请求

**Q6：`exposedHeaders` 是干什么的？不配会怎样？**

答题要点：
- 跨域时 JS 默认只能读 6 个安全响应头
- 自定义头（如 `X-Trace-Id`）必须通过 `exposedHeaders` 暴露
- 不配的话 `response.headers.get('X-Trace-Id')` 返回 `null`
- 这是 traceId 链路闭环的最后一环

**Q7：为什么生产环境常常不需要配 CORS？**

答题要点：
- 前端打包后由 Nginx 托管，`/api` 反向代理到后端
- 浏览器看到的是同源请求（同域名同端口）
- Nginx 到后端是服务器间调用，不受同源策略约束
- 好处：少一层攻击面，也不用维护白名单

### 陷阱题

**Q8：`Authorization` 头属于「凭证（credentials）」吗？**

答题要点：
- **不属于**。`Authorization` 只是普通请求头，归 `allowedHeaders` 管
- 「凭证」指 Cookie、TLS 客户端证书、HTTP 认证
- 但前端 axios 设了 `withCredentials: true` 时，服务端必须 `allowCredentials(true)`，否则请求被拒
- 所以 JWT 放 header 的场景，技术上不需要 `allowCredentials`

**Q9：预检请求会带上业务请求头吗？**

答题要点：
- 不会。`OPTIONS` 请求只带 `Origin`、`Access-Control-Request-Method`、`Access-Control-Request-Headers`
- 不带 `Authorization`、不带 body、不带自定义业务头
- **所以预检请求无法做认证**——服务端必须放行 `OPTIONS`

**Q10：`maxAge` 设太长会有什么问题？**

答题要点：
- 改 CORS 配置后客户端要等缓存过期才生效
- 调试期很痛苦（改了没反应）
- 生产可以设长（如 86400），开发期设短（如 600）

## 六、常见坑

| 坑 | 现象 | 原因 | 处理 |
| --- | --- | --- | --- |
| `allowedOrigins("*")` + credentials | 浏览器报 ACAO 不能是 `*` | 规范禁止 | 改 `allowedOriginPatterns` |
| 漏配 `exposedHeaders` | 前端读不到 `X-Trace-Id` | 跨域只暴露 6 个安全头 | 加 `exposedHeaders` |
| 加了 Security 后跨域失效 | 预检返回 401 | CORS 没挂到 Security | `http.cors(...)` + 放行 OPTIONS |
| `localhost` 和 `127.0.0.1` 混用 | 明明配了却还跨域 | 域名不同源 | 白名单两个都写 |
| 端口写错 | 5173 配成 5174 | Vite 端口会自动递增 | 白名单加范围或用 pattern |
| 生产环境还开着 CORS | 增加攻击面 | 没切到 Nginx 同源 | 部署时改配置 |
| `maxAge` 设太长 | 改配置不生效 | 预检结果被缓存 | 开发期设短 |

## 七、自检清单

- [ ] 能判断任意两个 URL 是否同源
- [ ] 说得出简单请求的三个条件，以及为什么本项目的 POST 都是预检
- [ ] 知道 `allowedOrigins` 和 `allowedOriginPatterns` 的关键差异
- [ ] 能解释 `*` + `credentials` 为什么被禁止
- [ ] 知道 `exposedHeaders` 和 04 篇 traceId 的联动关系
- [ ] 能复述「Security 导致跨域失效」的原因和解法
- [ ] 知道为什么生产环境用 Nginx 代理后不需要 CORS
- [ ] 清楚 `Authorization` 不属于 credentials
