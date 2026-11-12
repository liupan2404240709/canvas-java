# 05 · MyBatis-Plus 配置

> 对应源码：`config/MybatisPlusConfig.java`、`config/AuditMetaObjectHandler.java`
> 说明：本阶段 **没有 Mapper**（阶段 2 才建第一批表），所以这两个类现在是「空转」状态，但它们决定了后面所有 CRUD 的行为，必须提前配对。

## 一、一句话定位

`MybatisPlusConfig` 注册三个「SQL 改写器」，`AuditMetaObjectHandler` 负责「创建时间/更新时间自动填充」。

前者管**安全与正确性**（分页不会全表扫、并发更新不会覆盖、误操作不会清表），后者管**少写代码**（不用每次都 `setCreatedAt(LocalDateTime.now())`）。

## 二、完整代码

### MybatisPlusConfig.java

```java
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    @Bean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }
}
```

### AuditMetaObjectHandler.java

```java
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
```

## 三、逐段拆解

### 3.1 三个拦截器各管什么

#### ① `PaginationInnerInterceptor`（分页）

**没有它会发生什么**：`mapper.selectPage(new Page<>(1, 10), wrapper)` 会忽略分页参数，**把整张表的数据查出来再在内存里截取**。数据量上万时直接 OOM。

**它做的事**：在执行前把 SQL 改写成带 `LIMIT` 的形式，并额外发一条 `SELECT COUNT(*)`。

```sql
-- 原始 SQL
SELECT * FROM t_canvas WHERE deleted = 0

-- 改写后（第 2 页，每页 10 条）
SELECT COUNT(*) FROM t_canvas WHERE deleted = 0          ← 额外查询总数
SELECT * FROM t_canvas WHERE deleted = 0 LIMIT 10, 10    ← 分页查询
```

**两个参数**：

```java
pagination.setMaxLimit(200L);   // 单页最多 200 条
pagination.setOverflow(false);  // 页码超出总页数时，返回空列表而不是回到第一页
```

`maxLimit` 是**防滥用**：前端传 `pageSize=999999` 时不至于把库拖垮。返回的结果里 `size` 会被限制到 200。

> **注意**：`maxLimit` 是「会改变实际行为的边界值」。按项目规范，这种值应该先确认再落地。当前设 200 是基于「画布列表、素材列表」这类场景的经验值，阶段 2 做列表接口时如果发现不够用，可以调到 500 或按接口单独配置。

`overflow` 的两种行为对比：

| `overflow` | 请求 page=100（只有 5 页） | 前端表现 |
| --- | --- | --- |
| `false`（当前） | 返回空列表，`total` 仍是真实值 | 表格空，但分页器显示 5 页——用户知道翻过头了 |
| `true` | 自动回到第 1 页，返回第一页数据 | 用户以为翻到了第 100 页，实际看到第一页——**更迷惑** |

#### ② `OptimisticLockerInnerInterceptor`（乐观锁）

**解决的场景**：两个用户同时编辑同一份画布。

```
时刻  用户A                          用户B
T1    读取 canvas (version=1)
T2                                    读取 canvas (version=1)
T3    修改并保存 → version=2
T4                                    修改并保存 → 覆盖了 A 的修改（丢更新）
```

**加了乐观锁之后**，更新语句变成：

```sql
UPDATE t_canvas
SET title = ?, version = version + 1
WHERE id = ? AND version = 1        ← 关键：带上读到的版本号
```

B 执行时 `version` 已经是 2，`WHERE version = 1` 匹配不到，**影响行数为 0**。应用层判断 `affectedRows == 0` 就抛 `BizException(CONFLICT)`，提示「数据已被他人修改，请刷新后重试」。

**使用前提**（阶段 2 建表时必须满足）：

```java
@Version
private Integer version;            // 实体字段，必须有 @Version 注解
```

```sql
version INT NOT NULL DEFAULT 0      -- 表字段，不能为 null
```

**为什么叫「乐观」**：假设冲突很少发生，不加锁，只在写入时检查。对比「悲观锁」（`SELECT ... FOR UPDATE`）会一直占着行锁，并发差。**高并发读多写少的场景用乐观锁，写多冲突多才用悲观锁。**

#### ③ `BlockAttackInnerInterceptor`（防全表更新/删除）

**拦的是什么**：

```java
// 这两种会直接抛异常，不会执行
mapper.remove(new QueryWrapper<>());           // 没有 where 条件
mapper.update(entity, new UpdateWrapper<>());  // 没有 where 条件
```

异常信息类似：`Prohibition of table update operation`。

**为什么要拦**：一个手抖的 `update` 漏了 `eq`，就是全表被改。这个插件是**最后一道保险**，成本极低（一行业务代码都不用改）。

**注意它的边界**：它只拦「完全没有 WHERE」的情况。`WHERE 1=1` 或者 `WHERE deleted = 0`（全表条件）它管不了——所以它防的是「手滑漏写」，不是「恶意全表操作」。

### 3.2 为什么拦截器顺序不能乱

源码注释里写了「分页 → 乐观锁 → 防全表更新」，这是 MyBatis-Plus 官方推荐的顺序，原因是**内部拦截器通过 `beforeQuery` / `beforeUpdate` 等回调链式处理 SQL**，顺序不同会导致：

| 错误顺序 | 后果 |
| --- | --- |
| 乐观锁在分页之前 | 分页 SQL 被改写后，乐观锁找不到 `version` 条件，**失效** |
| 防全表攻击在乐观锁之前 | `UPDATE ... WHERE id=? AND version=?` 被判为「有 where」，没问题；但**纯全表 update** 会在加乐观锁前就被拦，错误信息不对 |
| 分页放在最后 | COUNT SQL 可能被其他拦截器二次改写，导致 `total` 不准 |

**面试可以这样答**：MyBatis-Plus 的 `MybatisPlusInterceptor` 是一个「内部拦截器容器」，内部按添加顺序依次执行 `willDoQuery` / `beforeQuery` / `beforeUpdate` 等回调。分页必须在最前，因为它要把原 SQL 替换成分页 SQL；乐观锁必须在更新类操作中尽早处理，因为它要改写 SET 和 WHERE。

### 3.3 `MetaObjectHandler` 自动填充

#### 用起来是什么效果

阶段 2 建实体时这样写：

```java
public class CanvasProject {
    @TableField(fill = FieldFill.INSERT)          // 插入时填充
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)   // 插入和更新都填充
    private LocalDateTime updatedAt;
}
```

然后：

```java
CanvasProject project = new CanvasProject();
project.setTitle("我的画布");
// 不用 set createdAt / updatedAt
projectMapper.insert(project);   // 数据库里两个字段都有值了
```

#### `strictInsertFill` 和 `fillStrategy` 的区别（重要）

| 方法 | 行为 |
| --- | --- |
| `fillStrategy(...)` | **只有当字段为空时才填充**（旧 API，已废弃） |
| `strictInsertFill(...)` | **只有当 `@TableField(fill=...)` 匹配时才填充**（新 API） |

`strict` 的「严格」体现在两点：
1. 必须有 `@TableField(fill = FieldFill.INSERT)` 注解才填充，防止误填到没有注解的字段
2. 会检查字段类型是否匹配（第三个参数 `LocalDateTime.class`）

**为什么 `insertFill` 里对 `updatedAt` 也用 `strictInsertFill`**：新建记录时，更新时间等于创建时间，这是业务常识。

**为什么 `updateFill` 里只填 `updatedAt`**：`createdAt` 在更新时不该变。

#### 一个必须知道的坑：`updateFill` 不总是生效

`MetaObjectHandler.updateFill` 只在**用实体对象更新**时触发：

```java
// ✅ 会触发
projectMapper.updateById(project);

// ❌ 不会触发（用的是 UpdateWrapper，没有实体对象）
projectMapper.update(null, new UpdateWrapper<CanvasProject>()
        .set("title", "新标题")
        .eq("id", 1L));
```

第二种写法要手动 `.set("updated_at", LocalDateTime.now())`。**这个坑在阶段 2 写「局部更新」接口时一定会踩**，到时候要统一约定：要么都用 `updateById`，要么手动补时间。

### 3.4 为什么现在没有 `@MapperScan`

启动日志里有这一行：

```
No MyBatis mapper was found in '[com.infinitecanvas.canvas]' package.
```

**原因**：MyBatis-Plus 的 starter 会自动扫描启动类所在包下的 `@Mapper` 接口，但现在 `mapper` 包**根本不存在**，所以扫了个空。

**为什么现在不加 `@MapperScan`**：

1. 加了也没用——没有接口可扫
2. 加了会把这个警告变成「预期内的配置」，反而让人以为配好了
3. **阶段 2 建第一个 Mapper 时再加**，加在 `MybatisPlusConfig` 上更内聚：

```java
@Configuration
@MapperScan("com.infinitecanvas.canvas.mapper")
public class MybatisPlusConfig { ... }
```

**面试点**：`@MapperScan` 和 `@Mapper` 二选一即可。前者集中配置（推荐，包路径一目了然），后者逐个标注（每个接口都要写，容易漏）。

## 四、设计决策

| 决策 | 选择 | 理由 | 替代方案与代价 |
| --- | --- | --- | --- |
| 分页实现 | `PaginationInnerInterceptor` | 物理分页，数据库层截断 | 内存分页（`PageHelper` 的 `RowBounds` 模式）——大表 OOM |
| `maxLimit` | 200 | 防前端传超大 pageSize | 不限——一次查全表 |
| `overflow` | `false` | 翻过头返回空，不误导用户 | `true`——用户以为翻到了实际回到首页 |
| 乐观锁 | 开启 | 阶段 2 画布多人编辑需要 | 不开——丢更新 |
| 防全表更新 | 开启 | 零成本保险 | 不开——一次手滑清表 |
| 自动填充 | `MetaObjectHandler` | 业务代码不写时间 | 数据库 `DEFAULT CURRENT_TIMESTAMP`——更新时还要靠 `ON UPDATE`，MyBatis 感知不到变化 |
| 填充 API | `strictInsertFill` | 有注解才填，防误填 | `fillStrategy`（废弃） |
| `@MapperScan` | **暂不加** | mapper 包为空 | 现在加——警告变成噪音 |

> 关于「自动填充 vs 数据库默认值」再展开一句：数据库的 `DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` 也能实现同样效果，而且更不容易漏。缺点有两个：一是实体对象在插入后拿不到填充后的值（要重新查一次），二是不同数据库语法不同（MySQL 和 PostgreSQL 写法不一样），可移植性差。**两者可以同时用**：数据库兜底，应用层显式填充，阶段 2 建表时我会都写上。

## 五、面试考察点

### 基础题

**Q1：MyBatis-Plus 的分页插件原理？**

答题要点：
- 实现 `InnerInterceptor` 接口，拦截 `Executor#query`
- 在 SQL 执行前改写：先查 `COUNT(*)` 得到总数，再拼 `LIMIT offset, size`
- 是**物理分页**（数据库层截断），不是查出来再截
- 对比：MyBatis 原生的 `RowBounds` 是逻辑分页（查全量再截），`PageHelper` 是拦截器 + ThreadLocal 传参

**Q2：乐观锁怎么实现的？**

答题要点：
- 实体字段加 `@Version`，表字段 `NOT NULL DEFAULT 0`
- 更新时 SQL 加 `WHERE version = #{oldVersion}`，`SET version = version + 1`
- 判断影响行数，为 0 说明被并发修改过
- 适用读多写少；写冲突多用悲观锁 `SELECT ... FOR UPDATE`

**Q3：`MetaObjectHandler` 怎么用？**

答题要点：
- 实现接口，重写 `insertFill` / `updateFill`
- 实体字段加 `@TableField(fill = FieldFill.INSERT)` 或 `INSERT_UPDATE`
- 用 `strictInsertFill` 而不是废弃的 `fillStrategy`
- 注意 `updateFill` 只对 `updateById(entity)` 生效，`UpdateWrapper` 方式不触发

### 进阶题

**Q4：三个拦截器的顺序为什么不能乱？**

答题要点：
- `MybatisPlusInterceptor` 内部按添加顺序执行回调
- 分页必须最先：它要把原 SQL 替换成分页 SQL，其他拦截器要基于改写后的 SQL 工作
- 乐观锁要在更新类拦截中靠前：它要改写 SET 和 WHERE
- 防全表攻击放最后：它是「兜底检查」，不应该改变 SQL
- 顺序错了的典型现象：分页 `total` 不准、乐观锁静默失效

**Q5：`BlockAttackInnerInterceptor` 能防止所有误删吗？**

答题要点：
- 不能。它只拦「完全没有 WHERE 子句」的 update/delete
- `WHERE 1=1`、`WHERE deleted=0` 这类「形式上有条件、实质是全表」的操作它管不了
- 真正的防线是：逻辑删除（`deleted` 字段）+ 权限控制 + 数据库账号权限最小化

> 本项目 `application.yml` 里配了 `logic-delete-field: deleted`，所有查询自动带 `deleted = 0`，删除变成 `UPDATE ... SET deleted = 1`，这是比 `BlockAttackInnerInterceptor` 更根本的防线。

**Q6：物理分页和逻辑分页的区别？**

答题要点：

| | 物理分页 | 逻辑分页 |
| --- | --- | --- |
| 实现 | SQL 加 `LIMIT` | 查全量，Java 里 `subList` |
| 内存 | 只加载当前页 | 加载全表 |
| 大表 | 可用 | OOM |
| 一致性问题 | 翻页期间数据变化会有漂移 | 同样有 |
| 代表 | MP 的 `PaginationInnerInterceptor` | MyBatis `RowBounds` |

**Q7：自动填充的时间用 `LocalDateTime` 还是 `Date`？**

答题要点：
- `LocalDateTime`：Java 8 时间 API，不可变、线程安全、无时区歧义
- `Date`：可变、设计缺陷多、`SimpleDateFormat` 线程不安全
- MyBatis 3.4.5+ 原生支持 `LocalDateTime`，MySQL 对应 `DATETIME`
- 序列化要配 `LocalDateTimeSerializer`（见 [06 篇](./06-JSON序列化.md)）

### 陷阱题

**Q8：加了乐观锁插件，但 `updateById` 没有生效，可能是什么原因？**

答题要点（4 个排查方向）：
1. 实体字段没加 `@Version` 注解
2. 表字段 `version` 为 NULL（`WHERE version = null` 永远不匹配）
3. 更新时传的实体里 `version` 是 null（没从数据库读就新建对象）
4. 用的是 `UpdateWrapper` 而不是 `updateById`，插件只处理实体更新

**Q9：`@TableField(fill = FieldFill.INSERT_UPDATE)` 和 `INSERT` 有什么区别？**

答题要点：
- `INSERT`：只在 `insertFill` 时填充
- `INSERT_UPDATE`：`insertFill` 和 `updateFill` 都填充
- `UPDATE`：只在 `updateFill` 时填充
- 选错的后果：`updatedAt` 用 `INSERT` 的话，更新时不会变

**Q10：不用 `MetaObjectHandler`，用数据库的 `ON UPDATE CURRENT_TIMESTAMP` 行不行？**

答题要点：
- 行，而且更不容易漏（不依赖应用层）
- 缺点一：插入后实体对象里的 `updatedAt` 还是 null，要重新查才能拿到
- 缺点二：跨数据库不通用（PostgreSQL 需要写触发器）
- 最佳实践：两者都配，数据库兜底，应用层显式赋值方便回显

## 六、常见坑

| 坑 | 现象 | 原因 | 处理 |
| --- | --- | --- | --- |
| 没加分页插件 | 查列表返回全表数据 | 分页参数被忽略 | 注册 `PaginationInnerInterceptor` |
| 拦截器顺序错 | `total` 不准 / 乐观锁失效 | 回调链顺序被打乱 | 分页 → 乐观锁 → 防攻击 |
| `version` 为 null | 乐观锁永远不生效 | 表字段允许 null | `NOT NULL DEFAULT 0` |
| `updateFill` 不触发 | `updatedAt` 不变 | 用了 `UpdateWrapper` | 手动 `.set()` 或改用 `updateById` |
| 用 `fillStrategy` | 字段有值时不覆盖 | 旧 API 语义是「为空才填」 | 换 `strictInsertFill` |
| mapper 包为空还加 `@MapperScan` | 启动警告 | 没有接口可扫 | 阶段 2 再加 |
| `maxLimit` 太小 | 前端要 500 条只返回 200 | 单页上限 | 按业务调整 |

## 七、自检清单

- [ ] 能说出三个拦截器各自防的是什么问题
- [ ] 能解释为什么顺序是「分页 → 乐观锁 → 防攻击」
- [ ] 说得清物理分页和逻辑分页的差异
- [ ] 知道乐观锁的 SQL 长什么样，什么时候判断冲突
- [ ] 能说出 `strictInsertFill` 比 `fillStrategy` 严格在哪
- [ ] 知道 `updateFill` 在什么情况下不触发
- [ ] 能解释为什么现在不加 `@MapperScan`
- [ ] 知道 `overflow=true` 和 `false` 的用户体验差异
