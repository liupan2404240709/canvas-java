# 02 · 实体与 ORM 映射

## 一、一句话定位

实体是**数据库行的 Java 表示**。这一篇讲清三个注解：`@Version`（并发）、`@TableLogic`（删除）、`@TableField(fill)`（审计字段），以及两个容易踩的类型映射。

## 二、完整代码

### CanvasProject

```java
@Data
@TableName("t_canvas_project")
public class CanvasProject {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;
    private String title;
    private String backgroundMode;
    private Integer showImageInfo;      // 0/1，不是 Boolean
    private Double viewportX;
    private Double viewportY;
    private Double viewportK;
    private Integer nodeCount;
    private Integer connectionCount;

    @Version
    private Integer version;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
```

### CanvasNode / CanvasEdge（附属表）

```java
@Data
@TableName("t_canvas_node")
public class CanvasNode {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long projectId;
    private String nodeKey;
    private String type;
    private String title;
    private Double posX;
    private Double posY;
    private Double width;
    private Double height;
    private String metadata;    // JSON 文本
}
```

**注意这两个类没有 `createdAt` / `updatedAt` 字段**。不是忘了，是刻意：批量插入不经过字段填充，让数据库默认值兜底更快，而且节点本来就没有"最后修改时间"的业务含义。

## 三、逐段拆解

### 3.1 `@TableId(type = IdType.ASSIGN_ID)`

雪花算法生成 19 位 `Long`。三个选项对比：

| 策略 | 生成方式 | 适用 |
| --- | --- | --- |
| `AUTO` | 数据库自增 | 单库简单，但分库要改造，且 id 可被枚举 |
| `ASSIGN_ID` | 雪花，应用侧生成 | **本项目**：不依赖数据库，分库天然支持 |
| `INPUT` | 手动传入 | 导入历史数据时用 |

雪花 id 是 `Long`，超过 JS 的 `Number.MAX_SAFE_INTEGER`（2^53），所以出参必须用 `@JsonSerialize(using = ToStringSerializer.class)` 转字符串，否则前端精度丢失——`ProjectSummaryVO` / `ProjectDetailVO` / `SaveSnapshotVO` 的 id 都标了。

### 3.2 `@Version`：让 `updateById` 自动带上版本条件

`@Version` 本身什么都不做，它只是给 `OptimisticLockerInnerInterceptor` 一个标记。加了之后：

```java
projectMapper.updateById(project);
```

实际发出：

```sql
UPDATE t_canvas_project
SET title=?, ..., version=?, updated_at=?
WHERE id=? AND version=? AND deleted=0
```

实测日志（阶段 2 验证实例）：

```
PREPARING: UPDATE t_canvas_project SET user_id=?, title=?, ... WHERE id=? AND version=? AND deleted=0
```

三点必须知道：

1. **`SET version = version + 1` 是拦截器改写的**，不是 MP 把新值传进来的。成功之后实体上的 `version` 也会被改成新值，所以能直接回给前端。
2. `WHERE version = ?` 用的是**实体当前值**。所以实体必须是从数据库读出来的、或者手动 set 过版本号。凭空 `new` 一个只设 id 的实体，version 是 null，等于没有乐观锁。
3. 影响行数为 0 = 版本不匹配，**不会抛异常**，要自己判断。

### 3.3 `@TableLogic`：删除变 UPDATE

```java
projectMapper.deleteById(id);
```

实际执行 `UPDATE t_canvas_project SET deleted=1 WHERE id=? AND deleted=0`。

同时**所有** select / update 都会自动追加 `deleted=0`。这正是回收站必须手写 SQL 的原因（见 05 篇）。

### 3.4 `@TableField(fill = ...)`：审计字段自动填

`createdAt` 只在 insert 填，`updatedAt` insert 和 update 都填。填充逻辑在 `AuditMetaObjectHandler`，本篇只提一点：

**`strictUpdateFill` 在 update 场景基本不生效**。它只在字段为 null 时填充，而 `updateById(entity)` 的 entity 通常是从库里读的、`updatedAt` 早就有旧值。本项目实测踩到了这个问题，改成 `setFieldValByName` 强制覆盖，详见 11 篇。

### 3.5 `Integer showImageInfo` 而不是 `Boolean`

数据库是 `TINYINT NOT NULL DEFAULT 0`。

用 `Boolean` 也能跑（MyBatis 有 BooleanTypeHandler），但三个隐患：

- `BIT(1)` 映射到 `Boolean` 在不同驱动下行为不一致；
- `Boolean` 为 null 时无法区分"没传"和"false"；
- 前端传 `true/false`，库里存 `1/0`，中间必然有一次转换，`Integer` 让转换点显式。

所以 VO 里做了转换：`isTrue(project.getShowImageInfo())` → `Boolean` 出参。**边界转一次，比在内部到处 `Integer == 1` 干净**。

### 3.6 `metadata` 用 `String` 而不是 `JsonNode`

| 方案 | 代价 |
| --- | --- |
| `String` + Service 里 `readTree` / `writeValueAsString` | 多两次序列化，但完全可控，出错日志里能看到原文 |
| `@TableField(typeHandler = JacksonTypeHandler.class)` + `JsonNode` | 要开 `@TableName(autoResultMap = true)`，类型处理失败时报错信息很难懂 |
| `Map<String, Object>` | 数字会退化成 `Double`（`1` 变 `1.0`），前端拿到会出问题 |

选 `String`。另外注意：**DTO/VO 层用 `JsonNode`**，因为那里不需要关心存储，只关心"原样透传"。转换只发生在 `toNodeEntity` / `toNodeVO` 两个方法里。

## 四、设计决策

| 决策 | 选了 | 替代 | 原因 |
| --- | --- | --- | --- |
| 主键 | 雪花 + 字符串出参 | 自增 | 不依赖库、可分布式；字符串出参防精度丢失 |
| 附属表审计字段 | 不映射 | 都加 `fill` | 批量插入少两次反射，节点不需要 |
| 布尔列 | `Integer` + VO 转 `Boolean` | `Boolean` 直通 | TINYINT/BIT 映射坑，且能区分 null |
| JSON 列 | `String` | `JsonNode` + TypeHandler | 可控、可调试 |

## 五、面试考察点

### 基础题

**Q1：MyBatis-Plus 的 `@TableId` 有几种策略？**

`AUTO`（库自增）、`ASSIGN_ID`（雪花）、`ASSIGN_UUID`、`INPUT`（手动）、`NONE`（跟随全局）。本项目的全局配置是 `id-type: assign_id`，实体上再显式标一次是为了"看这个类就知道 id 从哪来"。

**Q2：`@TableField(fill = ...)` 和数据库 `DEFAULT CURRENT_TIMESTAMP` 有什么区别？**

前者是应用侧赋值，能拿到 Java 的 `LocalDateTime`（含时区语义），也能在返回 VO 时直接用；后者是库侧兜底，应用不感知，插入后实体字段还是 null。本项目两者都用了：项目表用 fill（需要在响应里返回时间），节点表用数据库默认值（不需要返回）。

### 进阶题

**Q3：`@Version` 是怎么实现的？**

MP 的 `OptimisticLockerInnerInterceptor` 在 `update` 语句执行前改写 SQL：把 `version` 从 SET 列表里替换成 `version = version + 1`，并在 WHERE 追加 `version = #{原值}`。所以它依赖"实体里的 version 是最新读到的值"这个前提。

**Q4：如果 `updateById` 返回 0，一定是版本冲突吗？**

不一定。也可能是：① 行不存在；② 被逻辑删除了（`deleted=0` 条件不满足）。所以正确的处理是先查一次，或者像本项目这样——版本冲突有自己的业务码（40920），而"行不存在"在前面 `requireProject` 已经拦掉了，到这一步只剩版本冲突。

**Q5：`@TableLogic` 对 `updateById` 有影响吗？**

有。它会追加 `deleted = 0`，所以**永远无法用内置方法更新已删除的行**。要恢复必须手写 SQL。这是本篇和 05 篇的连接点。

### 陷阱题

**Q6：`Long` id 直接返回给前端会怎样？**

`2121533515925041153` 在 JS 里 `JSON.parse` 后变成 `2121533515925041200`，末尾几位被舍入。表现是"点进去 404"或"删错了"。修法：`ToStringSerializer` 或全局 `Jackson` 配置 `Long → String`。本项目**只在 id 字段上标**，因为全局改会把分页 `total`、`latencyMs` 也变成字符串。

**Q7：为什么 `CanvasNode` 不继承一个 `BaseEntity`？**

可以继承，但会强制所有表都有 `created_at/updated_at/deleted`。节点表不需要审计字段、也不做逻辑删除，硬套基类会造成"字段有一半是 null"或者"被迫加不必要的列"。**结构相似不等于该抽象**。

## 六、常见坑

| 坑 | 现象 | 处理 |
| --- | --- | --- |
| `new` 一个只设 id 的实体去 `updateById` | version 为 null，乐观锁失效 | 先 `selectById` 拿全量，或用 `UpdateWrapper` |
| 忘记 `mapper-locations` | `Invalid bound statement` | 显式配 `classpath*:/mapper/**/*.xml` |
| `@TableLogic` 配了但 yml 没配 | 删除变成物理删除 | `global-config.db-config.logic-delete-field` 要和注解一致 |
| 实体字段名和列名不对应 | 查询返回 null | `map-underscore-to-camel-case: true` + 列名用 snake_case |
| 用 `Boolean` 接 `TINYINT` | 有的环境 null，有的 false | 统一用 `Integer`，VO 层转换 |

## 七、自检清单

- [ ] 说得清 `@Version` 依赖什么前提，以及返回 0 的三种可能
- [ ] 知道 `@TableLogic` 会让哪些方法"看不到"已删除数据
- [ ] 能解释为什么 id 要序列化成字符串，以及为什么不全局改
- [ ] 说得出 `metadata` 用 `String` 而不是 TypeHandler 的理由
- [ ] 知道 `Integer` / `Boolean` 映射的坑在哪
