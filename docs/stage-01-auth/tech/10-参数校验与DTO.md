# 10 · 参数校验与 DTO

## 一句话定位

DTO 是"接口的入参契约"，它和 Entity 的职责完全不同：**Entity 面向表结构，DTO 面向调用方**。混用会同时带来安全和维护问题。

## 完整代码

```java
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Pattern(regexp = "^[a-zA-Z0-9_]{4,20}$", message = "用户名需为 4-20 位字母、数字或下划线")
        String username,

        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需为 8-64 位")
        String password,

        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname,

        @Email(message = "邮箱格式不正确")
        @Size(max = 128, message = "邮箱最长 128 个字符")
        String email
) { }
```

## 逐段拆解

### ① 为什么不用 Entity 接参

假设直接用 `User` 接：

```java
@PostMapping("/register")
public Result<?> register(@RequestBody User user) { ... }
```

| 问题 | 具体表现 |
| --- | --- |
| **越权赋值**（mass assignment） | 客户端可以传 `{"id": 1, "status": 1, "deleted": 0}`，如果代码里 `updateById(user)` 就会改掉别人的记录 |
| **字段泄露** | Entity 有 `passwordHash`，前端传什么就塞什么，等于让前端直接指定密码哈希 |
| **耦合** | 表加一个字段，接口契约就被动变化；接口改字段名，数据库也得跟着改 |
| **校验无处安放** | Entity 上的校验注解会被所有复用它的场景继承，包括不该校验的场景 |

DTO 的核心价值是**只暴露该暴露的字段**。`RegisterRequest` 里根本没有 `id`、`status` 这些字段，客户端传了也会被 Jackson 忽略（默认 `FAIL_ON_UNKNOWN_PROPERTIES=false`）。

### ② 为什么用 `record` 而不是普通类 + Lombok

| | record | Lombok `@Data` |
| --- | --- | --- |
| 行数 | 10 行 | 3 行（但字段要重复声明） |
| 可变性 | 不可变（final 字段，无 setter） | 可变 |
| 语义 | 明确表达"这是一份数据" | 表达"这是一个对象" |
| 校验注解 | 可直接标在组件上 | 标在字段上 |

DTO/VO 天然适合不可变：反序列化后只读，不存在"中途被谁改了"的风险。项目里的 `Result`、`UserVO`、`TokenVO` 也都是 record。

**注意**：record 的校验注解写在组件上时，Lombok/Jackson 都能正确处理；但如果用 `@JsonProperty` 重命名，要注意注解要能落到底层字段上。

### ③ 校验注解怎么生效的

```
@Valid @RequestBody RegisterRequest
   ↓
RequestResponseBodyMethodProcessor 解析参数
   ↓
发现有 @Valid → 触发 Validator 校验
   ↓
失败抛 MethodArgumentNotValidException
   ↓
GlobalExceptionHandler 捕获，拼成 "字段: 原因; 字段: 原因"
```

三个阶段都不能少：

1. Controller 参数上必须有 `@Valid`（或 `@Validated`）——**不加注解，`@NotBlank` 完全不生效**，这是最高频的"校验没起作用"原因；
2. 类路径要有 `spring-boot-starter-validation`（阶段 0 已加）；
3. 全局异常处理器要捕获 `MethodArgumentNotValidException`（阶段 0 已写）。

### ④ 每个注解的选择理由

| 注解 | 用在哪 | 为什么不用别的 |
| --- | --- | --- |
| `@NotBlank` | 用户名、密码 | 它比 `@NotNull` 多判空串和纯空格；`@NotEmpty` 不判空格 |
| `@Pattern` | 用户名、头像地址 | 只做格式约束，不做业务约束（"是否已存在"必须查库） |
| `@Size` | 密码、昵称、邮箱 | 限制长度，防止超长字符串打爆数据库（`VARCHAR(32)` 存 1000 字符会报错或截断） |
| `@Email` | 邮箱 | 只用它做粗筛，真正的验证靠发邮件 |

**`@Email` 的坑**：它默认接受空字符串（`""` 视为合法），所以邮箱可选时不用额外加 `@NotBlank`；但它**不校验域名是否存在**，也不拒绝 `a@b`（没有顶级域）这种。真要严格，正则比它可靠。

### ⑤ 密码为什么是 8-64 位

- **下限 8**：NIST 建议最少 8 位。再长会影响体验，再短容易被字典覆盖。
- **上限 64**：防止超长字符串消耗 BCrypt 的 CPU（BCrypt 虽然只取 72 字节，但字符串本身的处理、传输、日志都会受影响）。

**注意**：`@Size` 数的是**字符数**不是字节数。中文密码 24 个字就超过 BCrypt 的 72 字节限制了，这个已知问题在 05 篇详细讨论。

### ⑥ 昵称可不填，为什么不用 `@NotBlank`

```java
@Size(max = 32, message = "昵称最长 32 个字符")
String nickname
```

`@Size` 对 `null` 是**通过**的（null 不参与长度校验）。这意味着不传昵称也能注册，业务层用 `StringUtils.hasText()` 兜底成用户名。

如果写成 `@NotBlank`，前端就必须传昵称——但注册页只要求填用户名密码是更常见的产品设计。

### ⑦ `UpdateProfileRequest` 用 `@Pattern` 校验头像地址

```java
@Pattern(regexp = "^$|^(https?://|/).*", message = "头像需为 http(s) 地址或站内路径")
```

为什么限制协议：如果允许 `javascript:alert(1)` 这样的值，前端直接 `<img src>` 或 `<a href>` 使用时可能触发 XSS。

**注意这个注解的两个细节**：
1. `^$` 允许空字符串（表示"清空头像"）；
2. `@Pattern` 对 `null` 是通过的（null 不校验），所以"不传头像"也合法。

这正是"null 表示不改、空串表示清空"的语义区分——在 12 篇的 `updateProfile` 里配合使用。

### ⑧ DTO 里要不要做业务校验

```java
// 不要这样
public record RegisterRequest(...) {
    public void validateUsernameNotExists(UserMapper mapper) { ... }
}
```

DTO 只做**格式和边界**校验（不依赖数据库、不依赖其他 Bean）。业务校验（用户名是否已存在、验证码是否有效）必须放 Service——否则 DTO 就要注入 Mapper，变成一个"四不像"。

判断标准：**这条校验能不能纯靠入参本身完成？** 能 → DTO；不能 → Service。

## 设计决策对比

| 决策 | 选的 | 替代 | 代价 |
| --- | --- | --- | --- |
| 类型 | record | Lombok 类 | 不可变更安全，代价是不能继承（DTO 也不需要） |
| 校验位置 | 注解 + 全局异常处理 | 手写 if 判断 | 声明式更简洁，代价是错误文案要自己维护 |
| 错误文案 | 每条写中文 | 用默认文案 | 可读性好，代价是代码变长 |
| 未知字段 | 忽略（Jackson 默认） | 报错（`FAIL_ON_UNKNOWN_PROPERTIES`） | 忽略更宽容，前端多传字段不会 400 |

## 面试考察点

**基础**

- `@NotNull` / `@NotEmpty` / `@NotBlank` 的区别？—— 后者多一个"去空格后非空"的判断，只用于字符串。
- `@Valid` 和 `@Validated` 的区别？—— 前者是 JSR-303 标准，用于参数和方法返回值；后者是 Spring 的，多了分组校验能力，还能标在类上做方法级校验。
- 校验失败抛什么异常？怎么统一处理？—— `MethodArgumentNotValidException`（请求体）和 `ConstraintViolationException`（`@RequestParam`/`@PathVariable` 上的约束）。

**进阶**

- 什么是 mass assignment（批量赋值）漏洞？怎么防？—— 用 DTO 白名单字段，不要让框架按请求字段自动赋值到实体。
- 嵌套对象怎么校验？—— 内层字段所在类上加 `@Valid`，外层参数也要 `@Valid`。
- 分组校验怎么用？—— 定义标记接口（如 `Create.class`、`Update.class`），`@Validated(Create.class)` 指定校验哪组。
- 自定义校验注解怎么写？—— 实现 `ConstraintValidator<注解, 类型>`，`isValid` 返回 boolean。

**陷阱**

- "加了 `@NotBlank` 但没生效"—— 忘了在参数上加 `@Valid`。
- "`@Size(min=1)` 对 null 报错吗"—— 不报错，null 通过；需要非空必须配 `@NotNull`。
- "在 DTO 里注入 Mapper"—— 循环依赖、职责混乱。

## 常见坑

1. 校验注解的 `message` 里**不要用单引号**：`{0}` 这类占位符语法会和消息插值冲突。
2. `@Pattern` 的正则默认是"整个字符串完全匹配"，不需要自己加 `^...$`；加了也无害，语义更清晰。
3. 如果接口用 `@RequestParam` 接参，`@Valid` 不起作用，要在 Controller 类上标 `@Validated`，异常类型也变成 `ConstraintViolationException`。
4. Jackson 反序列化 record 时依赖 `-parameters` 编译参数（Spring Boot 的 parent 已默认开启），自己搭工程时忘了会报"cannot deserialize"。

## 自检清单

- [ ] 能说出 Entity 直接接参的四个问题
- [ ] 知道 `@Valid` 必须加在参数上，否则注解完全失效
- [ ] 能区分 `@NotNull`/`@NotEmpty`/`@NotBlank`
- [ ] 能解释为什么业务校验不能写进 DTO
- [ ] 知道 `@Size` 数的是字符不是字节
- [ ] 能说出 `null` 和空串在本项目里的语义区别
- [ ] 了解 mass assignment 漏洞的成因
