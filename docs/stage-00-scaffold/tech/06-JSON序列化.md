# 06 · JSON 序列化（时间格式）

> 对应源码：`config/JacksonConfig.java`

## 一、一句话定位

这个类是为了修一个**「配了以为生效、其实没生效」**的坑：Spring Boot 里 `spring.jackson.date-format` 对 `LocalDateTime` **完全无效**。

如果不写这个类，接口返回的时间会是这样：

```json
"checkedAt": "2026-11-11T20:58:36.6997966"
```

带 7 位纳秒，前端解析没问题，但看着脏、长度不可控、跨语言对接容易出问题。

## 二、完整代码

```java
@Configuration
public class JacksonConfig {

    /** 秒级 ISO-8601，前端 new Date(...) 可直接解析，也不带一串纳秒。 */
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer javaTimeCustomizer() {
        return builder -> {
            builder.serializers(new LocalDateTimeSerializer(DATE_TIME));
            builder.deserializers(new LocalDateTimeDeserializer(DATE_TIME));
        };
    }
}
```

配套的 `application.yml`：

```yaml
spring:
  jackson:
    time-zone: GMT+8
    default-property-inclusion: non_null
```

> 注意：这里**故意没有** `date-format`。第一阶段写的时候配了，实测发现对 `LocalDateTime` 没用，已经删掉，避免误导。

## 三、逐段拆解

### 3.1 坑的完整经过

**第一次配置**（错误做法）：

```yaml
spring:
  jackson:
    date-format: yyyy-MM-dd HH:mm:ss
    time-zone: GMT+8
```

**当时的预期**：所有时间都输出成 `2026-11-11 20:55:31`。

**实测输出**：

```json
"checkedAt": "2026-11-11T20:58:36.6997966"
```

格式完全没变，还多了纳秒。

**为什么**：`spring.jackson.date-format` 最终设置的是 `ObjectMapper#setDateFormat(DateFormat)`，而 `DateFormat` 是 **`java.util.Date` 专用**的类型。`LocalDateTime` 走的是 `JavaTimeModule` 里注册的 `LocalDateTimeSerializer`，**根本不经过 `DateFormat`**。

**类比**：`date-format` 是给旧世界的 `Date` 用的钥匙，`LocalDateTime` 是新世界的锁，钥匙对不上。

**这也是为什么这个坑很隐蔽**：配置写上去了、不报错、`Date` 类型的字段表现「正常」，只有 `LocalDateTime` 不动。如果你项目里两种时间类型混用，就更难发现。

### 3.2 为什么用 `Jackson2ObjectMapperBuilderCustomizer` 而不是自己定义 `ObjectMapper`

这是**必须讲对的一个点**。对比两种写法：

#### ❌ 错误写法：自己 new 一个 ObjectMapper

```java
@Bean
public ObjectMapper objectMapper() {
    ObjectMapper mapper = new ObjectMapper();
    JavaTimeModule module = new JavaTimeModule();
    module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME));
    mapper.registerModule(module);
    return mapper;
}
```

**后果**：Spring Boot 的 `JacksonAutoConfiguration` 上有 `@ConditionalOnMissingBean`，你一自定义，**Boot 精心配好的 ObjectMapper 全部失效**：

| 失效的默认配置 | 后果 |
| --- | --- |
| `default-property-inclusion: non_null` | null 字段重新出现在 JSON 里 |
| `time-zone: GMT+8` | 时区退回 UTC |
| `WRITE_DATES_AS_TIMESTAMPS=false` | 时间变成时间戳数字 |
| `FAIL_ON_UNKNOWN_PROPERTIES=false` | 前端多传一个字段就报 400 |
| Spring Boot 注册的所有 Module | 包括 `ParameterNamesModule`（影响构造器推断） |

#### ✅ 正确写法：`Jackson2ObjectMapperBuilderCustomizer`

```java
@Bean
public Jackson2ObjectMapperBuilderCustomizer javaTimeCustomizer() {
    return builder -> {
        builder.serializers(...);
        builder.deserializers(...);
    };
}
```

`Jackson2ObjectMapperBuilder` 是 Boot 用来构建 ObjectMapper 的**工厂**。Customizer 是「在工厂构建过程中插入一步」，**是增量修改，不是替换**。

所有 Customizer 会在构建时依次执行，最终产出一个「Boot 默认配置 + 你的自定义」的 ObjectMapper。

**面试答题要点**：Boot 自动配置的 Bean 一律用「Customizer / Configurer」扩展，不要自己定义同类型 Bean——因为自动配置类上普遍有 `@ConditionalOnMissingBean`，你定义了就把它整个顶掉了。

**同样的模式还有**：

| 想改什么 | 该用的扩展点 | 不该做的 |
| --- | --- | --- |
| Jackson | `Jackson2ObjectMapperBuilderCustomizer` | 定义 `ObjectMapper` Bean |
| MVC 配置 | `WebMvcConfigurer` | `@EnableWebMvc`（会禁用全部 MVC 自动配置） |
| 数据源 | `DataSourceProperties` / 自定义 `DataSource` | 这个场景确实需要自定义 Bean |
| 线程池 | `TaskExecutorBuilder` | 定义 `ThreadPoolTaskExecutor` 覆盖 Boot 的（可以但要注意别名） |

### 3.3 为什么格式选 `yyyy-MM-dd'T'HH:mm:ss`（ISO 风格）而不是 `yyyy-MM-dd HH:mm:ss`

这是**有争议的**，两种都很常见。选择 ISO 风格（带 `T`）的理由：

**① 前端 `new Date()` 能直接解析**

```javascript
new Date("2026-11-11T22:00:23")      // ✅ 所有浏览器都能解析
new Date("2026-11-11 22:00:23")      // ⚠️ Safari 报 Invalid Date
```

Safari 对空格分隔的格式解析一直有问题（历史上是它最严格）。带 `T` 的 ISO 8601 是 ECMAScript 规范明确要求支持的唯一格式。

**② 与 `LocalDateTime.toString()` 的默认输出兼容**

`LocalDateTime.toString()` 输出的就是 `2026-11-11T22:00:23`。保持格式一致，日志里和响应里看到的是同一种写法。

**③ 跨语言友好**

Go、Python、Java 的时间库都默认认 ISO 8601。空格分隔不是标准格式。

**代价**：人读起来不如 `2026-11-11 22:00:23` 舒服，且前端展示时要自己格式化（`dayjs(x).format('YYYY-MM-DD HH:mm:ss')`）。

**另一派的观点**：REST 接口就是给人看的居多，用空格更友好，前端拿到直接渲染。这个取舍没有绝对对错，**关键是整个项目保持一致**——如果阶段 2 改了格式，所有接口一起改。

### 3.4 为什么去掉纳秒

`LocalDateTime.now()` 的精度取决于底层时钟，Windows 上通常能到 100 纳秒级。默认序列化会输出全部精度：

```
2026-11-11T20:58:36.6997966     ← 7 位小数
```

**问题**：

1. **长度不稳定**：有时 0 位（整秒）、有时 9 位，日志对齐难看
2. **前端显示丑**：直接 `toString()` 会出现一串无意义的数字
3. **比较和去重麻烦**：`2026-11-11T20:58:36.6997966` 和 `2026-11-11T20:58:36.69979660` 是同一个时刻但字符串不等

**秒级够用吗**：对业务来说，`createdAt`、`updatedAt`、`checkedAt` 精确到秒完全够。如果以后需要毫秒（比如任务耗时统计），改 pattern 成 `yyyy-MM-dd'T'HH:mm:ss.SSS` 即可——但要注意前端解析兼容性。

> 顺带一提：`HealthVO.latencyMs` 用的是 `long` 毫秒数，不走时间格式，所以探针耗时的精度不受影响。

### 3.5 反序列化也要配

```java
builder.deserializers(new LocalDateTimeDeserializer(DATE_TIME));
```

**只配序列化不够**。如果前端传时间过来（比如查询条件 `startTime: "2026-11-11T00:00:00"`），后端要能解析。

默认的 `LocalDateTimeDeserializer` 能解析带纳秒的 ISO 格式，但配上固定 pattern 后，**格式必须完全匹配**：

```
"2026-11-11T00:00:00"       ✅
"2026-11-11T00:00:00.123"   ❌ 报错
"2026-11-11 00:00:00"       ❌ 报错
```

**这是阶段 2 做「按时间范围筛选画布」时会踩的坑**：前端如果传 `2026-11-11 00:00:00`（空格），会抛 `HttpMessageNotReadableException`，被全局处理器翻译成「请求体格式错误」——文案很模糊，查起来费劲。

**到时候的解法**：要么前端统一传 ISO，要么把反序列化器改成宽松模式（用 `DateTimeFormatter.ofPattern("yyyy-MM-dd[ ]['T']HH:mm:ss")` 同时兼容两种）。

### 3.6 `time-zone: GMT+8` 管什么

```yaml
spring:
  jackson:
    time-zone: GMT+8
```

它设置的是 Jackson 的 `TimeZone`，影响：

| 类型 | 是否受 `time-zone` 影响 |
| --- | --- |
| `java.util.Date` | ✅ 会按这个时区格式化 |
| `LocalDateTime` | ❌ **不受影响**（它本身就没有时区概念） |
| `ZonedDateTime` | ✅ 影响输出的偏移量 |
| `Instant` | ✅ 影响输出 |

**这里有个容易混淆的点**：`LocalDateTime` 是「无时区的本地时间」，`2026-11-11T22:00:23` 就是字面意思上的晚上 10 点，不存在「转成哪个时区」的问题。

那么 `LocalDateTime.now()` 取的是哪个时区？**取 JVM 的默认时区**。所以真正决定时间对不对的是：

```bash
# 启动参数
-Duser.timezone=Asia/Shanghai

# 或者 MySQL 连接串里的
serverTimezone=Asia/Shanghai
```

本项目在 `application-dev.yml` 的 JDBC URL 里已经指定了 `serverTimezone=Asia/Shanghai`，容器里也设了 `TZ: Asia/Shanghai`，两边一致，不会出现「数据库存的是 14 点、接口返回 22 点」的错位。

**面试可以聊**：`LocalDateTime` 的时区陷阱是「看着有值，但差了 8 小时」。根因通常是应用服务器时区和数据库时区不一致，加上 `serverTimezone` 参数写错。

## 四、设计决策

| 决策 | 选择 | 理由 | 替代方案与代价 |
| --- | --- | --- | --- |
| 扩展方式 | `Jackson2ObjectMapperBuilderCustomizer` | 增量修改，保留 Boot 默认配置 | 自定义 `ObjectMapper`——顶掉全部默认配置 |
| 时间类型 | `LocalDateTime` + 显式序列化器 | Java 8 API，线程安全 | `Date` + `date-format`——可变、线程不安全 |
| 格式 | `yyyy-MM-dd'T'HH:mm:ss` | ISO 8601，`new Date()` 直接可解析 | 空格分隔——Safari 解析报错 |
| 精度 | 秒级 | 业务够用，长度稳定 | 纳秒——字符串长度不定，人读困难 |
| 反序列化 | 同 pattern | 严格对称 | 不配——用默认的宽松解析，前后端格式不一致时会静默出错 |
| null 输出 | `non_null` 全局排除 | 减小响应体 | 保留 null——前端要判两次 |

## 五、面试考察点

### 基础题

**Q1：Spring Boot 里怎么统一配置 JSON 时间格式？**

答题要点：
- 如果字段是 `java.util.Date`：`spring.jackson.date-format` 生效
- 如果是 `LocalDateTime`：**配 `date-format` 无效**，要注册 `LocalDateTimeSerializer`
- 注册方式用 `Jackson2ObjectMapperBuilderCustomizer`，不要自定义 `ObjectMapper` Bean
- 也可以用字段级 `@JsonFormat(pattern = "...")`，但重复且容易漏

**Q2：`Jackson2ObjectMapperBuilderCustomizer` 和直接定义 `ObjectMapper` Bean 有什么区别？**

答题要点：
- `ObjectMapper` 是 Boot 自动配置的 Bean，带 `@ConditionalOnMissingBean`
- 自己定义会**完全替换**，丢掉 `non_null`、时区、Module 注册等所有默认配置
- Customizer 是「构建过程中的一个回调」，增量修改，保留默认
- 结论：扩展自动配置的 Bean 一律用扩展点，不要覆盖

**Q3：`LocalDateTime` 和 `Date` 有什么区别，为什么用前者？**

答题要点：

| | `LocalDateTime` | `Date` |
| --- | --- | --- |
| 可变性 | 不可变 | 可变（`setTime`） |
| 线程安全 | 安全 | 非线程安全 |
| 时区 | 无时区，纯本地时间 | 内部是 UTC 毫秒数 |
| 格式化 | `DateTimeFormatter`（线程安全） | `SimpleDateFormat`（**非线程安全**） |
| API | 丰富（`plusDays`、`withDayOfMonth`） | 简陋 |

`SimpleDateFormat` 线程不安全是经典面试题：多线程共享会解析出错误结果，必须每次 new 或加锁。

### 进阶题

**Q4：为什么 `spring.jackson.date-format` 对 `LocalDateTime` 无效？**

答题要点：
- 它最终设置的是 `ObjectMapper#setDateFormat(DateFormat)`
- `DateFormat` 是 `java.util.Date` 体系的抽象
- `LocalDateTime` 由 `JavaTimeModule` 里的 `LocalDateTimeSerializer` 处理，走的是 `DateTimeFormatter`，不经过 `DateFormat`
- 所以 `Date` 字段会变、`LocalDateTime` 字段不变

**Q5：`LocalDateTime` 的时区问题怎么理解？**

答题要点：
- `LocalDateTime` **本身不含时区**，`2026-11-11T22:00:23` 就是字面的 22:00
- `LocalDateTime.now()` 用的是 JVM 默认时区
- `spring.jackson.time-zone` 对它无效
- 真正要对齐的是：应用服务器时区、数据库时区、JDBC `serverTimezone` 参数
- 需要跨时区时用 `ZonedDateTime` 或 `Instant`

**Q6：前端传 `"2026-11-11 00:00:00"`（空格）后端报 400，怎么排查？**

答题要点：
- 异常是 `HttpMessageNotReadableException`，被全局处理器翻译成「请求体格式错误」
- 原因：配置了 `LocalDateTimeDeserializer` 固定 pattern，空格格式不匹配
- 解决：统一前后端格式，或把反序列化器改成兼容两种格式的 `DateTimeFormatter`

### 陷阱题

**Q7：你自定义了 `ObjectMapper` Bean，项目里 `non_null` 突然失效了，为什么？**

答题要点：
- `JacksonAutoConfiguration` 的 `jacksonObjectMapper` Bean 上有 `@ConditionalOnMissingBean`
- 自定义后自动配置不生效，`application.yml` 里的 `spring.jackson.*` 全部失效
- 这些配置是通过 `Jackson2ObjectMapperBuilder` 应用的，Builder 不再参与构建就不会生效

**Q8：`@JsonFormat(pattern = ...)` 和全局配置冲突了怎么办？**

答题要点：
- 字段级注解**优先级更高**，覆盖全局配置
- 所以可以全局配 ISO，个别字段用 `@JsonFormat` 单独指定
- 但大范围用字段注解会导致风格不统一，优先用全局

**Q9：为什么序列化器只配了 `LocalDateTime`，`LocalDate` 和 `LocalTime` 呢？**

答题要点：
- 当前只配了 `LocalDateTime`，`LocalDate` / `LocalTime` 走默认
- 默认输出：`"2026-11-11"`、`"22:00:23"`——其实已经是干净的 ISO 格式，不需要额外配置
- 需要的话同理注册 `LocalDateSerializer` / `LocalTimeSerializer`

## 六、常见坑

| 坑 | 现象 | 原因 | 处理 |
| --- | --- | --- | --- |
| 只配 `date-format` | `LocalDateTime` 还是带纳秒 | 该配置对 Java 8 时间无效 | 注册 Serializer |
| 自定义 `ObjectMapper` | `non_null`、时区全部失效 | `@ConditionalOnMissingBean` | 用 Customizer |
| 只配序列化不配反序列化 | 前端传时间报 400 | 默认解析器格式不匹配 | 同时注册 Deserializer |
| 格式改了前端没同步 | 前端 `Invalid Date` | 前后端格式不一致 | 写进接口文档 |
| 应用和数据库时区不一致 | 时间差 8 小时 | JVM 时区 ≠ `serverTimezone` | 统一成 `Asia/Shanghai` |
| 用了 `yyyy-MM-dd HH:mm:ss` | Safari 下 `new Date()` 失败 | 非标准格式 | 用 ISO 带 `T` |

## 七、自检清单

- [ ] 能解释 `spring.jackson.date-format` 为什么对 `LocalDateTime` 无效
- [ ] 说得清 `Jackson2ObjectMapperBuilderCustomizer` 和自己定义 ObjectMapper 的区别
- [ ] 知道 `@ConditionalOnMissingBean` 在自动配置里的作用（和 01 篇呼应）
- [ ] 能说出 ISO 格式比空格分隔好在哪里（至少三条）
- [ ] 理解 `LocalDateTime` 为什么不受 `time-zone` 影响
- [ ] 知道反序列化不配会有什么后果
