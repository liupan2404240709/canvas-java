# 06 · 批量插入与 XML Mapper

## 一、一句话定位

30 个节点如果循环 `insert`，就是 30 次网络往返。批量插入把它们压成**一条 SQL、一次往返**。

## 二、完整代码

```xml
<insert id="insertBatch">
    INSERT INTO t_canvas_node
    (id, project_id, node_key, type, title, pos_x, pos_y, width, height, metadata)
    VALUES
    <foreach collection="list" item="item" separator=",">
        (#{item.id}, #{item.projectId}, #{item.nodeKey}, #{item.type}, #{item.title},
        #{item.posX}, #{item.posY}, #{item.width}, #{item.height}, #{item.metadata})
    </foreach>
</insert>
```

```java
@Mapper
public interface CanvasNodeMapper extends BaseMapper<CanvasNode> {
    int insertBatch(@Param("list") List<CanvasNode> list);
}
```

调用方：

```java
private void insertNodes(Long projectId, List<CanvasNode> nodes) {
    if (nodes.isEmpty()) {
        return;              // 空列表直接返回，否则 SQL 语法错误
    }
    nodes.forEach(node -> node.setProjectId(projectId));
    nodeMapper.insertBatch(nodes);
}
```

## 三、生成的 SQL 长什么样

实测日志：

```
==>  Preparing: INSERT INTO t_canvas_node (id, project_id, node_key, type, title,
    pos_x, pos_y, width, height, metadata) VALUES (?,?,?,?,?,?,?,?,?,?), (?,?,?,?), ...
==> Parameters: 2121533...(Long), 2121533...(Long), n_0(String), image(String), ...
```

30 个节点 × 10 列 = **300 个参数**，一次执行。

## 四、为什么选 XML 而不是 `@Insert` 注解

| 方案 | 问题 |
| --- | --- |
| 循环 `insert` | N 次往返，N 次事务日志刷盘 |
| `@Insert` + `<script>` 拼 foreach | 注解里写 XML 字符串，没高亮、没校验、难维护 |
| **XML Mapper** | 有语法高亮和 IDE 校验，`<sql>` 片段可复用 |
| MP 的 `saveBatch` | 需要 Service 继承 `ServiceImpl`，本项目 Service 是普通类 |

`saveBatch` 还有一点要注意：它默认是**分批的 JDBC batch**（`addBatch` + `executeBatch`），不是多值 INSERT。要真正拼成多值 SQL，得配合 `rewriteBatchedStatements=true`。

## 五、`rewriteBatchedStatements=true` 是什么

JDBC URL 里已经带了（阶段 0 配的）：

```
jdbc:mysql://127.0.0.1:3307/infinite_canvas?...&rewriteBatchedStatements=true
```

它的作用：MySQL 驱动发现你在用 `addBatch` 时，会把多条语句**改写成一条多值 INSERT**再发送。

| 配置 | 30 条 insert 的实际行为 |
| --- | --- |
| `false`（默认） | 30 个独立的 COM_QUERY 包 |
| `true` | 1 个包，`INSERT ... VALUES (...),(...)...` |
| 配了多值 SQL（本项目） | 1 个包，**不依赖这个参数**也快 |

也就是说本项目是双保险：即使有人把这个参数去掉，多值 SQL 依然是一次往返；而 `saveBatch` 这种写法就必须靠它。

**注意**：它只对 `INSERT`/`UPDATE` 的 batch 生效，且要求 SQL 模板一致。另外它会让 `getGeneratedKeys` 行为变化，所以插入后不要指望逐行拿回 auto_increment 主键——本项目主键是应用侧雪花生成的，不受影响。

## 六、为什么主键要在 Java 侧生成

`@TableId(type = IdType.ASSIGN_ID)` 在插入前就把 id 填进实体了。所以：

- 批量插入时不需要数据库回填主键；
- 不会因为 `rewriteBatchedStatements` 导致主键丢失；
- 事务还没提交时，实体里已经有 id，可以立刻拿去做关联插入（比如先插节点、再插引用了节点 id 的连线）。

如果换成 `AUTO` 自增 + 批量插入，你就得等 `executeBatch` 返回的主键数组，还容易因为驱动行为差异出问题。

## 七、批量插入的边界

| 边界 | 本项目的处理 |
| --- | --- |
| 空列表 | `if (nodes.isEmpty()) return;` 否则 `VALUES` 后面什么都没有，SQL 报错 |
| 单次节点数上限 | 500（`@Size` 校验），防止一条 SQL 过大撞 `max_allowed_packet` |
| 单次连线数上限 | 2000 |
| 单条 SQL 长度 | 500 节点 × 约 200 字节 ≈ 100KB，远小于默认 64MB 的 `max_allowed_packet` |

如果真要做"一次导入 5 万个节点"，就得改成分片：每 500 条执行一次。本项目在 500 的上限内，不需要。

## 八、设计决策

| 决策 | 选了 | 替代 | 原因 |
| --- | --- | --- | --- |
| 批量方式 | XML 多值 INSERT | `saveBatch` | 不用改 Service 继承结构，SQL 可见 |
| 主键生成 | 应用侧雪花 | 库自增 | 批量插入时可预知 id |
| 参数上限 | 500 / 2000 | 不限制 | 防超长 SQL，也符合画布实际规模 |

## 九、面试考察点

### 基础题

**Q1：批量插入有几种实现方式？**

1. 循环单条 insert（最差）
2. JDBC `addBatch` / `executeBatch`（MP 的 `saveBatch` 底层）
3. 多值 `INSERT ... VALUES (...),(...)`（本项目）
4. `LOAD DATA INFILE`（大数据量导入）

**Q2：`<foreach>` 的几个属性分别是什么？**

`collection`（集合名，`@Param` 决定）、`item`（元素别名）、`index`（下标）、`separator`（分隔符）、`open`/`close`（包裹符号）。

### 进阶题

**Q3：多值 INSERT 和 batch 哪个快？**

在 MySQL + `rewriteBatchedStatements=true` 下两者最终都是多值 SQL，性能接近。差别在于：多值 INSERT 有单条 SQL 长度上限，batch 可以分片；多值 INSERT 的 SQL 在日志里完整可见，便于排查。

**Q4：为什么 30 次单条 insert 在本地也感觉不到慢？**

本地回环 RTT 约 0.05ms，30 次才 1.5ms。但跨机房 RTT 2ms 时就是 60ms——**本地测不出的性能问题，往往是网络往返**。这是面试里"为什么批量"最好的答法。

**Q5：批量插入失败了怎么定位是哪条数据错？**

多值 INSERT 报错只给整体错误，不给"第几条"。常见做法：捕获异常后降级为逐条插入并记录失败项。本项目在插入前已经做了完整校验（id 重复、引用存在、字段非空），所以走到 insert 时数据是干净的。

### 陷阱题

**Q6：`<foreach>` 里用 `#{item.metadata}` 插入 JSON 列，需要转义吗？**

不需要。`#{}` 是预编译参数，不是字符串拼接，天然防注入，也能正确处理引号和中文。

**Q7：空列表不判断会怎样？**

生成 `INSERT INTO ... VALUES`（后面没内容），MySQL 报语法错误。这个坑在 `copy` 一个空画布时必然触发——本项目在 `insertNodes` 里判空了。

## 十、常见坑

| 坑 | 现象 | 处理 |
| --- | --- | --- |
| 空列表没判 | SQL 语法错误 | `isEmpty` 直接返回 |
| 参数过多 | `PacketTooBigException` | 分片，或限制单次数量 |
| 用 `AUTO` 主键 + 批量 | 拿不到 id | 用应用侧生成 |
| foreach 里写 `${}` | SQL 注入 | 一律 `#{}` |
| 忘记 `@Param` | `Parameter 'list' not found` | 单参数集合也要加 |

## 十一、自检清单

- [ ] 说得出批量插入的四种方式及取舍
- [ ] 知道 `rewriteBatchedStatements` 到底改写了什么
- [ ] 能解释为什么主键在应用侧生成更适合批量
- [ ] 说得出空列表不判空的后果
- [ ] 能回答"为什么本地测不出性能差距"
