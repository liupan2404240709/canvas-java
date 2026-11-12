# 阶段 0 代码技术说明 · 索引

这组文档和 `stage-00-scaffold/` 下的三份文档定位不同，先分清：

| 文档集 | 回答什么问题 | 什么时候看 |
| --- | --- | --- |
| `README.md` | 怎么把它跑起来、怎么验收 | 第一次启动 |
| `design.md` | 整体分层、请求链路、决策记录（D-01~D-07） | 想理解架构全貌 |
| `api.md` | 接口长什么样、错误码含义 | 写前端或联调 |
| **`tech/`（本目录）** | **每个类为什么这么写、面试会怎么问** | **准备面试、复习原理** |

## 文档清单

| 编号 | 文档 | 覆盖的源码 | 核心考点 |
| --- | --- | --- | --- |
| 01 | [启动类与自动装配](./01-启动类与自动装配.md) | `CanvasApplication` | `@SpringBootApplication` 三个注解分别做了什么、自动装配原理 |
| 02 | [统一响应契约](./02-统一响应契约.md) | `Result` `ResultCode` `PageResult` | 泛型方法、`record` vs Lombok、HTTP 状态码与业务码分离 |
| 03 | [全局异常处理](./03-全局异常处理.md) | `BizException` `GlobalExceptionHandler` | `@ControllerAdvice` 原理、异常传播顺序、信息泄露防护 |
| 04 | [链路追踪](./04-链路追踪.md) | `TraceId` `TraceIdFilter` | 过滤器 vs 拦截器、MDC 与 ThreadLocal、`OncePerRequestFilter` |
| 05 | [MyBatis-Plus 配置](./05-MyBatisPlus配置.md) | `MybatisPlusConfig` `AuditMetaObjectHandler` | 三个拦截器作用与顺序、自动填充原理 |
| 06 | [JSON 序列化](./06-JSON序列化.md) | `JacksonConfig` | `LocalDateTime` 序列化坑、`Jackson2ObjectMapperBuilderCustomizer` |
| 07 | [Redis 配置](./07-Redis配置.md) | `RedisConfig` | 序列化器选择、`RedisTemplate` vs `StringRedisTemplate` |
| 08 | [CORS 跨域](./08-CORS跨域.md) | `WebMvcConfig` | 预检请求、`allowCredentials` 与 `*` 的冲突 |
| 09 | [接口文档](./09-接口文档.md) | `OpenApiConfig` | OpenAPI 3、SpringDoc 与 Swagger 2 的区别 |
| 10 | [健康检查](./10-健康检查.md) | `HealthController` `HealthService` `HealthVO` | 分层职责、探针设计、`Callable` 收口 |
| 11 | [配置文件](./11-配置文件.md) | `application.yml` `application-dev.yml` `logback-spring.xml` | 配置优先级、profile、日志滚动策略 |
| 12 | [依赖与容器](./12-依赖与容器.md) | `pom.xml` `docker-compose.yml` | starter 机制、依赖作用域、健康检查编排 |

## 建议阅读顺序

```mermaid
graph LR
    A[01 启动类] --> B[11 配置文件]
    B --> C[02 统一响应]
    C --> D[03 异常处理]
    D --> E[04 链路追踪]
    E --> F[10 健康检查]
    F --> G[05 MyBatis-Plus]
    G --> H[07 Redis]
    H --> I[06 Jackson]
    I --> J[08 CORS]
    J --> K[09 OpenAPI]
    K --> L[12 依赖与容器]
```

顺序的理由：先搞清楚「应用怎么起来的」，再搞清楚「配置从哪来」，然后是「一个请求怎么出去的」（响应 → 异常 → 追踪 → 业务），最后是「基础设施怎么接的」。

## 一篇文档的结构

每篇都按这个模板写，方便你按需跳读：

1. **一句话定位** —— 这个类在架构里扮演什么角色
2. **完整代码** —— 带行号，可直接对照源码
3. **逐段拆解** —— 每段做了什么、为什么这么写、不这么写会怎样
4. **设计决策** —— 有争议的地方给出替代方案对比表
5. **面试考察点** —— 分「基础题 / 进阶题 / 陷阱题」，每题给答题要点
6. **常见坑**
7. **自检清单** —— 能不能不看文档把这些问题答出来

## 面试话术总纲

如果面试官问「介绍一下你这个项目」，不要从阶段 0 开始背类名，按这条线讲：

> 这是一个前后端分离的 AI 图片创作工作台。后端用 Spring Boot 3 + MyBatis-Plus + MySQL + Redis。
> 我在工程骨架阶段做了三件事：
> **一是统一了接口契约**——所有响应都是 `code/message/data/traceId` 四段式，业务码和 HTTP 状态码分离，前端只判断 `code`，网关看 HTTP 状态；
> **二是统一了异常出口**——业务异常用 `BizException` 抛出，全局处理器翻译成统一结构，未知异常只回通用文案、细节留日志，避免 JDBC 异常里的表名和 SQL 片段泄露；
> **三是给每个请求打了 traceId**——通过 Filter 写进 MDC，日志格式里带出来，同时回写响应头，用户报错截图就能直接定位到那一次请求。
> 这三件事的价值是：后面加 10 个模块，接口风格不会走样，线上排障有统一入口。

这段话背后对应的就是 01~04 四篇文档。先能流利讲出这段，再往下补细节。

## 一个提醒

文档里的代码片段都是从**当前源码**复制的，如果之后改了代码，记得回来同步——面试时讲的和仓库里写的不一致，比不知道更糟。
