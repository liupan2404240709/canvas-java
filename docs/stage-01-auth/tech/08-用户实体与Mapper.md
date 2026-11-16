# 08 · 用户实体与 Mapper

## 一句话定位

`User` 是 `t_user` 表的 Java 映射，`UserMapper` 因为继承了 `BaseMapper` 而**一行 SQL 都不用写**就具备了增删改查能力。这一篇讲清楚"为什么能这样"。

## 完整代码

```java
@Data
@TableName("t_user")
public class User {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;
    private String email;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private Integer status;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    public static final int STATUS_ENABLED = 1;

    public boolean active() {
        return status != null && status == STATUS_ENABLED;
    }
}
```

```java
public interface UserMapper extends BaseMapper<User> {
}
```

```java
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.infinitecanvas.canvas.mapper")
public class CanvasApplication { ... }
```

## 逐段拆解

### ① `@TableName` 可以省吗

表名 `t_user` 和类名 `User` 不一致，所以必须显式指定。如果不写，MyBatis-Plus 会按默认规则找 `user` 表——而 `user` 在 MySQL 8 里**不是保留字**（`USER` 是函数名但不是保留字），所以不会报语法错，只会报"table 'user' doesn't exist"，很容易误判成连接问题。

### ② `IdType.ASSIGN_ID` 意味着什么

| 策略 | 谁生成 | 特点 |
| --- | --- | --- |
| `AUTO` | 数据库自增 | 简单，但分库分表难、id 可被枚举（`/user/1`、`/user/2`） |
| `ASSIGN_ID`（当前） | MyBatis-Plus 雪花算法 | 全局唯一、趋势递增、不暴露总量 |
| `INPUT` | 业务自己填 | 灵活，但要自己保证唯一 |

雪花 ID 是 64 位 long：`1 位符号 + 41 位时间戳 + 10 位机器 id + 12 位序列号`。19 位十进制数，**超出 JavaScript `Number.MAX_SAFE_INTEGER`（2^53）**，所以出参必须转成字符串——这就是 `UserVO` 上 `@JsonSerialize(using = ToStringSerializer.class)` 的原因。

**为什么不全局配置 Long → String**：阶段 0 的 02 篇讲过，全局改会把分页 `total`、耗时 `latencyMs` 也变成字符串，前端算数直接崩。所以只在 ID 字段上标注。

### ③ `@TableId` 在 insert 后会自动回填 id

```java
userMapper.insert(user);
log.info("用户注册成功 userId={}", user.getId());   // 这里已经有值了
```

不需要 `useGeneratedKeys`：MyBatis-Plus 在 `insert` 前用雪花算法生成 id 并 set 进对象，然后才执行 SQL。所以 insert 之后对象里就有 id，可以直接用来签发 token。

### ④ `@TableField(fill = ...)` 与 `AuditMetaObjectHandler` 的配合

阶段 0 写好的 `AuditMetaObjectHandler` 会在 insert 时填充 `createdAt` / `updatedAt`，在 update 时填充 `updatedAt`。但它**只对标注了 `fill` 的字段生效**——注解是"开关"，Handler 是"执行者"。

**为什么不在数据库用 `DEFAULT CURRENT_TIMESTAMP`**：数据库默认值只在"SQL 里没写这一列"时生效。如果用 `updateById`，MyBatis-Plus 会把非 null 字段都拼进 SQL，`updatedAt` 如果是 null 就不会出现在 SQL 里，靠数据库的 `ON UPDATE` 也能更新——但应用层拿不到更新后的时间（对象里还是旧值/空值）。用 Handler 填充则应用层和数据库一致。

注意 schema.sql 里两个都写了（`DEFAULT CURRENT_TIMESTAMP` + `ON UPDATE`），这是双保险：万一有绕过应用的直接 SQL 写入，时间也是对的。

### ⑤ `@TableLogic`：查询自动加条件，删除变成 UPDATE

```java
@TableLogic
private Integer deleted;
```

效果：

| 你写的 | 实际执行的 SQL |
| --- | --- |
| `selectById(1)` | `SELECT ... WHERE id = 1 AND deleted = 0` |
| `deleteById(1)` | `UPDATE t_user SET deleted = 1 WHERE id = 1 AND deleted = 0` |
| `selectOne(wrapper)` | 自动在 WHERE 末尾追加 `AND deleted = 0` |

**好处**：数据可追溯，误删能恢复（阶段 2 的画布数据尤其需要）。
**代价**：所有查询都多一个条件，索引要带上 `deleted`；唯一索引和逻辑删除会打架（见 11 篇）。

**注意**：`application.yml` 里已经配了全局 `logic-delete-field: deleted`，理论上可以不写 `@TableLogic`。两个都写了不冲突（注解优先），显式写出来是为了让读代码的人一眼看到。

### ⑥ `active()` 为什么刻意不叫 `isEnabled()`

Lombok 的 `@Data` 会对 `boolean` 字段生成 `isXxx()`。如果这里写 `isEnabled()`，Bean 内省会认为这个类有一个 `enabled` 属性——将来如果哪天把 `User` 直接序列化成 JSON（比如调试接口），就会多出一个 `enabled` 字段，和数据库的 `status` 语义重复且可能不一致。

叫 `active()` 就只是个普通方法，不参与属性推导。

### ⑦ `@MapperScan` 放在启动类

```java
@MapperScan("com.infinitecanvas.canvas.mapper")
```

两种写法对比：

| 方式 | 优点 | 缺点 |
| --- | --- | --- |
| 每个接口上写 `@Mapper` | 直观 | 新增 Mapper 容易忘 |
| 启动类 `@MapperScan` | 一次声明全局生效 | 包路径写错时启动才报 |

`@MapperScan` 的内部实现是一个 `MapperScannerConfigurer`，它扫描指定包下所有接口，为每个接口生成 `MapperFactoryBean`，再由 MyBatis 用 **JDK 动态代理**生成实现类并注册进容器。所以 `UserMapper` 没有实现类也能注入。

**为什么阶段 0 故意没加**：mapper 包为空时 MyBatis 会打印 `No MyBatis mapper was found` 警告。现在有第一个 Mapper 了，加上正好消掉这条警告。

### ⑧ `BaseMapper` 提供的 17 个方法

常用的是：`insert` / `deleteById` / `updateById` / `selectById` / `selectList` / `selectPage` / `selectCount` / `selectOne`。

`selectOne` 有个坑：**结果超过 1 条会抛 `TooManyResultsException`**。我们的 `findByAccount` 用 `username = ? OR email = ?` 查询，理论上存在"某人的用户名恰好是另一人的邮箱"的情况，所以加了 `.last("LIMIT 1")` 兜底——虽然极小概率，但一旦发生就是线上 500。

## 设计决策对比

| 决策 | 选的 | 替代 | 代价 |
| --- | --- | --- | --- |
| 主键策略 | 雪花 ID | 自增 | 复杂一点，换来不可枚举、可分片 |
| 删除方式 | 逻辑删除 | 物理删除 | 多一个字段和索引负担，换来可恢复 |
| 实体字段 | 包含 `passwordHash` | 拆成独立的 `UserCredential` 表 | 拆表更安全（密码不会随实体到处跑），但阶段 1 不值得 |
| 时间填充 | MetaObjectHandler | 数据库默认值 | 应用层能拿到值，代价是多一个 Handler |

## 面试考察点

**基础**

- MyBatis 的 Mapper 接口没有实现类，为什么能注入？—— `MapperScannerConfigurer` + `MapperFactoryBean` + JDK 动态代理。
- `#{}` 和 `${}` 的区别？—— 前者是 PreparedStatement 占位符（防注入），后者是字符串拼接（有注入风险），排序字段名这类只能用 `${}` 时必须白名单校验。
- 逻辑删除的实现原理？—— 拦截器改写 SQL，delete 变 update，select 追加条件。

**进阶**

- 雪花算法的结构？为什么趋势递增？—— 41 位毫秒时间戳在高位。
- 雪花 ID 为什么要转成字符串给前端？—— 超过 JS 安全整数范围，会精度丢失。
- 逻辑删除和唯一索引为什么冲突？—— 删除后 `deleted=1`，再插入同名记录会撞唯一索引。解决方案：唯一索引加 `deleted` 列（但只能删一次）、删除时把唯一字段改名（如 `name + '_' + id`）、用 `deleted_at` 时间戳代替 0/1。
- `BaseMapper` 的 `insert` 会不会插入 null 字段？—— 默认 `FieldStrategy.NOT_NULL`，null 字段不出现在 SQL 里，所以数据库默认值才能生效。

**陷阱**

- "实体直接返回给前端"—— 会把 `passwordHash`、`deleted` 一起吐出去。
- "`selectOne` 没加 limit"—— 多条结果直接抛异常。
- "`@TableLogic` 加了但查询没生效"—— 全局配置的 `logic-delete-field` 和注解字段名不一致，或用了自定义 XML SQL（XML 里的 SQL 不会被自动加条件）。

## 常见坑

1. `map-underscore-to-camel-case: true` 必须开，否则 `password_hash` 映射不到 `passwordHash`（不报错，只是值是 null，非常难查）。
2. `deleted` 字段在 insert 时是 null，靠数据库 `DEFAULT 0` 兜底。如果哪天把数据库默认值删了，所有 insert 出来的记录 `deleted` 都是 null，而 `deleted = 0` 的条件匹配不到 null，会**查不到自己刚插入的数据**。
3. `@MapperScan` 的包路径不要写成 `com.infinitecanvas.canvas`（太宽），会把非 Mapper 接口也扫进去报错。

## 自检清单

- [ ] 能解释 Mapper 接口没有实现类为什么能注入
- [ ] 知道四种主键策略的差异和选用理由
- [ ] 能说出雪花 ID 转字符串的原因
- [ ] 能解释 `@TableField(fill=...)` 和 `MetaObjectHandler` 的分工
- [ ] 知道逻辑删除的 SQL 改写行为，以及它和唯一索引的冲突
- [ ] 能说出 `selectOne` 的隐藏风险
- [ ] 知道 `#{}` 和 `${}` 的区别
