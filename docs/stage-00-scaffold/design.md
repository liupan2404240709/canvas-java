# 阶段 0 设计文档：工程骨架与基础约定

## 1. 设计目标

这个阶段不写业务，只做三件事：

1. **定契约**：所有接口的响应结构、错误码、分页结构、时间格式从此固定，后续模块只填业务。
2. **定链路**：一个请求从进来到出去，经过哪些层、日志怎么串、异常在哪收口。
3. **定基础设施**：MySQL、Redis、ORM、文档、跨域一次配好，避免每个模块重复折腾。

一条设计原则贯穿整个后端：**能在公共层解决的，不写进业务代码。**  Controller 里出现 `try/catch`、`new HashMap<>()` 拼响应、手动 `setCreateTime`，都说明公共层没做好。

## 2. 分层与包结构

| 包 | 职责 | 不该出现的东西 |
| --- | --- | --- |
| `controller` | 接参、`@Valid` 校验、调用 service、包 `Result` | 业务判断、SQL、事务 |
| `service` | 业务规则、事务边界、编排多个 mapper / 三方调用 | `HttpServletRequest`、`ResponseEntity` |
| `mapper` | 数据访问，继承 `BaseMapper` | 业务分支 |
| `entity` | 与表一一对应 | 展示逻辑、脱敏注解 |
| `dto` | 入参对象 + 校验注解 | 数据库字段（如 `deleted`） |
| `vo` | 出参对象 | 密码、密钥等敏感字段 |
| `config` | 中间件与框架配置 | 业务逻辑 |
| `infra` | 过滤器、拦截器、外部系统适配 | 业务流程 |
| `common` | 响应体、错误码、异常、常量 | 依赖 `service` |

`entity` 和 `vo` 分开，是为了避免「查出来的实体直接返回」导致密码哈希、API Key 密文、`deleted` 标记被序列化出去。这是后面阶段 1、阶段 5 一定会踩的坑，现在就定死。

> 本阶段 `HealthService` 没有拆 interface + impl。规则是：**有第二个实现或需要 AOP 代理切面时才抽接口**，否则只是多一个文件。阶段 2 之后如果某个 service 需要接缓存切面，再抽。

## 3. 一次请求的完整链路

```
浏览器
  │  GET /api/v1/health  (Header: X-Trace-Id 可选)
  ▼
Tomcat ── context-path=/api 剥掉前缀
  ▼
TraceIdFilter           生成/透传 traceId → MDC → 响应头
  ▼
DispatcherServlet
  ▼
HealthController        参数绑定、@Valid
  ▼
HealthService           业务 + 探针
  ▼
JdbcTemplate / RedisTemplate
  ▼
Result.ok(data)         从 MDC 取 traceId 填进响应体
  ▼
GlobalExceptionHandler  仅在抛异常时介入
  ▼
浏览器  200 { code, message, data, traceId }
```

关键点：`traceId` 在 Filter 里就写进 MDC，所以这一条链路上任何一层的日志（包括 MyBatis 打印的 SQL）都会自动带上它。排查"用户说刚才保存失败了"这种问题时，让他把响应里的 traceId 发过来，`grep` 一次就能拿到完整上下文。

## 4. 统一响应设计

```json
{ "code": 0, "message": "success", "data": {...}, "traceId": "..." }
```

几个决定及理由：

**① `code = 0` 表示成功，不用 200。**
业务码和 HTTP 状态码是两套体系。HTTP 200 只代表"这个请求被正确处理了"，业务上可能失败（比如"画布不存在"）。用 0 表示成功，是后端最常见的约定，也方便前端写 `if (res.code === 0)` 一刀切。

**② 同时维护 `code` 和 `httpStatus`。**
`ResultCode` 里每个枚举都带 `httpStatus`。这样"未登录"既是 `40100` 业务码，也真的返回 HTTP 401。好处是 Nginx 访问日志、浏览器 DevTools、监控告警都能按状态码过滤，不用解析 body。代价是要维护两张表的映射关系 —— 值得。

**③ `traceId` 放在响应体里，同时回写响应头。**
响应体给用户截图，响应头给前端日志上报。两个都留，成本几乎为零。

**④ 失败时 `data` 为 null，且全局配置 `default-property-inclusion: non_null`。**
`data` 为 null 时字段直接不出现，响应更干净。副作用是前端不能依赖 `data` 字段一定存在，要写 `res.data?.list ?? []`。

**⑤ 时间统一 `yyyy-MM-dd'T'HH:mm:ss`（秒级 ISO-8601）。**

这里踩过一个坑值得记下来：最开始在 `application.yml` 里配了 `spring.jackson.date-format: yyyy-MM-dd HH:mm:ss`，结果接口返回的是 `2026-11-11T20:58:36.6997966` —— **`date-format` 只对 `java.util.Date` 生效，对 `LocalDateTime` 完全不起作用**。`LocalDateTime` 走的是 JavaTimeModule，默认输出带纳秒的 ISO 字符串。

正确做法是在 `JacksonConfig` 里注册 `LocalDateTimeSerializer`：

```java
builder.serializers(new LocalDateTimeSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")));
builder.deserializers(new LocalDateTimeDeserializer(...));
```

选 ISO 而不是 `yyyy-MM-dd HH:mm:ss`，是因为前者能被 JS `new Date()` 直接解析，后者在部分浏览器里是非法格式。同时把 `date-format` 从 yml 里删掉，避免造成"已经配好了"的错觉。

## 5. 错误码分段规划

| 段 | 用途 | 示例 |
| --- | --- | --- |
| `0` | 成功 | — |
| `400xx` | 参数与请求格式 | `40000` 参数有误 |
| `401xx` | 身份认证 | `40100` 未登录、`40101` token 过期 |
| `403xx` | 权限 | `40300` 无权访问该画布 |
| `404xx` | 资源不存在 | `40400` 画布不存在 |
| `409xx` | 状态冲突 | `40900` 版本冲突、`40901` 会话已切换 |
| `429xx` | 限流 | `42900` 请求过于频繁 |
| `500xx` | 服务端 | `50000` 未预期异常 |
| `6xxxx` | 三方服务 | 阶段 5/6 引入，如 `60001` 模型渠道调用失败 |

约定：**新增错误码只追加，不复用已废弃的编号**。前端可能按码做特殊处理，改语义会导致线上行为突变。

## 6. 全局异常处理策略

| 异常 | 处理 | HTTP | 日志级别 |
| --- | --- | --- | --- |
| `BizException` | 用自带的 `ResultCode` + 自定义 message | 按枚举 | warn |
| `MethodArgumentNotValidException` | 拼 `字段: 原因` | 400 | 不记 |
| `ConstraintViolationException` | 直接取 message | 400 | 不记 |
| `HttpMessageNotReadableException` | 固定文案"请求体格式错误" | 400 | warn |
| `NoResourceFoundException` | "接口不存在: {path}" | 404 | 不记 |
| `Exception` | 通用文案，堆栈进日志 | 500 | error |

两个细节：

- **业务异常不打印堆栈**。业务异常是"预期内的分支"，打堆栈会让日志噪音爆炸，线上真正的问题反而被淹没。
- **未知异常不回显 `e.getMessage()`**。JDBC 异常里可能带表名、SQL 片段甚至连接串，属于信息泄露。统一给"服务暂时不可用"，具体内容留在日志。

## 7. MyBatis-Plus 配置说明

三个拦截器，缺一不可：

| 插件 | 作用 | 不配会怎样 |
| --- | --- | --- |
| `PaginationInnerInterceptor` | 自动改写 `LIMIT`，并统计 total | `Page` 参数被忽略，永远返回全表 |
| `OptimisticLockerInnerInterceptor` | 配合 `@Version` 做乐观锁 | 多标签页同时保存画布会互相覆盖 |
| `BlockAttackInnerInterceptor` | 拦截无 where 的 update/delete | 一次手滑 `update(null)` 清空整张表 |

**顺序必须按官方建议**：分页 → 乐观锁 → 防全表更新。原因是这些插件是责任链，后一个会改写前一个产出的 SQL；分页必须在最前面拿到原始 SQL 才能正确拼 `LIMIT`。

另外设了 `pagination.setMaxLimit(200L)`：前端传 `pageSize=99999` 时最多只取 200 条，防止一次拖垮数据库。这个上限是自定的经验值，后面如果素材库需要批量导出，再单独开不分页的导出接口，而不是放大这个限制。

`AuditMetaObjectHandler` 负责 `createdAt` / `updatedAt` 自动填充。前提是实体字段标注：

```java
@TableField(fill = FieldFill.INSERT)
private LocalDateTime createdAt;

@TableField(fill = FieldFill.INSERT_UPDATE)
private LocalDateTime updatedAt;
```

用 `strictInsertFill` 而不是 `setFieldValByName`，是为了只在字段为 null 时填充，不覆盖调用方显式传入的值（比如数据迁移时想保留原始时间）。

**本阶段故意没有加 `@MapperScan`**：现在 `mapper` 包还是空的，扫不到 mapper 会打一条 `No MyBatis mapper was found` 警告，容易让人误以为配置错了。阶段 2 建第一个 Mapper 时一起加上，注释里会写清楚。

## 8. Redis 序列化

```java
key   → StringRedisSerializer
value → GenericJackson2JsonRedisSerializer
```

默认的 `JdkSerializationRedisSerializer` 会在 key 前面加乱码前缀、value 存成二进制，`redis-cli` 打开完全看不懂，排查缓存问题时非常痛苦。

换成 JSON 后：

```
127.0.0.1:6379> keys canvas:*
1) "canvas:snapshot:1839201"
127.0.0.1:6379> get canvas:snapshot:1839201
"{\"@class\":\"com.infinitecanvas.canvas.vo.CanvasSnapshotVO\",\"id\":\"1839201\",...}"
```

注意 `@class` 字段：`GenericJackson2JsonRedisSerializer` 会写入类型信息以便反序列化回具体类型。代价是**缓存里的类全限定名不能随便改**，改包名或类名会导致老缓存反序列化失败。处理方式：改类名时顺手删掉相关 key，或者后续换成显式指定类型的 `Jackson2JsonRedisSerializer<T>`。

## 9. 配置分层

```
application.yml        公共：端口、context-path、Jackson、MyBatis-Plus 全局策略、Swagger、CORS 白名单
application-dev.yml    本地：MySQL/Redis 地址、Hikari 参数、SQL 日志
application-prod.yml   阶段 8 再加：环境变量注入、关闭 SQL 日志、连接池调大
```

三条纪律：

1. **密码不进代码仓库**。现在 dev 用 `root/root` 是为了本地一键起，阶段 8 上生产必须改成 `${MYSQL_PASSWORD}` 环境变量。
2. **SQL 日志只在 dev 开**。`log-impl: StdOutImpl` 会把每条 SQL 打到控制台，生产环境既影响性能又泄露数据。
3. **同一份配置不要写两遍**。dev 只写"和公共不一样的部分"，靠 profile 覆盖，不复制整份 yml。

## 10. 关键决策记录

| 编号 | 决策 | 备选方案 | 理由 |
| --- | --- | --- | --- |
| D-01 | `context-path=/api` + 控制器写 `/v1/xxx` | 每个 Controller 手写 `/api/v1` 前缀 | 前缀只配一次，加新模块不会漏写；Nginx 转发规则也简单 |
| D-02 | 不做全局 `Long → String` 序列化 | 全局注册 `ToStringSerializer` | 全局转换会把分页 `total`、`latencyMs` 也变成字符串，前端算数会出错。改为在 VO 的 ID 字段上单独标注，阶段 2 落地 |
| D-03 | `Result` 用 `record` 而不是 `class` | Lombok `@Data` 类 | 响应体不可变，record 自动生成构造/`equals`/`toString`，序列化行为与普通 POJO 一致 |
| D-04 | 健康检查返回 200 + `status` 字段 | 不可用时返回 503 | 阶段 0 主要给人和浏览器看，200 更方便直接打开；阶段 8 接 Actuator 后再补标准 503 语义 |
| D-05 | 探针用 `JdbcTemplate` 而非 MyBatis | 写一个 `select 1` 的 Mapper | 健康检查不该依赖业务 ORM 配置，直连数据源最可靠 |
| D-06 | 日志用 logback 默认实现，不引 Log4j2 | Log4j2 高性能异步 | 当前量级完全够用，多一个依赖多一份配置负担 |
| D-07 | `HealthService` 不抽接口 | `HealthService` + `HealthServiceImpl` | 只有一个实现且无切面需求，接口只会增加跳转成本 |

## 11. 已知取舍与后续优化

| 项 | 现状 | 什么时候处理 |
| --- | --- | --- |
| 异步线程 traceId 丢失 | `@Async` 线程里 `MDC` 为空 | 阶段 6 做异步生成任务时，用 `TaskDecorator` 传递 MDC |
| 无接口耗时统计 | 只有 traceId | 阶段 8 加 AOP 或 Actuator |
| 无统一登录态 | 接口全匿名 | 阶段 1 |
| 无审计字段（创建人） | 只有时间 | 阶段 1 引入用户后，加 `createdBy` |
| SQL 日志用 `StdOutImpl` | 打印到控制台 | 阶段 8 换 p6spy 或关掉 |
| 没有单元测试 | 只有手测 | 阶段 2 开始每个 service 至少一个测试 |
