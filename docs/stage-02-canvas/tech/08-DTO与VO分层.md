# 08 · DTO / VO 分层

## 一、一句话定位

同一个"节点"，在**入参**、**实体**、**出参**、**导出文件**里是四个不同的类。这一篇讲为什么要接受这种"重复"。

## 二、四层结构

```
HTTP 请求 ──→ CanvasNodeDTO        （入参：带校验注解）
                ↓ 转换
                CanvasNode          （实体：对应表结构，metadata 是 String）
                ↓ 转换
HTTP 响应 ←── CanvasNodeVO         （出参：metadata 是 JsonNode）
                ↓ 转换
导出文件 ←── 复用 CanvasNodeVO       （格式稳定性由 ProjectExportVO 保证）
```

| 类 | 位置 | 字段差异 |
| --- | --- | --- |
| `CanvasNodeDTO` | `dto/` | 有 `@NotBlank` `@NotNull` `@Size` `@PositiveOrZero` |
| `CanvasNode` | `entity/` | `posX`/`posY` 拆开，`metadata` 是 `String`，有 `id`/`projectId` |
| `CanvasNodeVO` | `vo/` | `position` 是嵌套对象，`metadata` 是 `JsonNode`，无 `projectId` |
| `ProjectExportVO` | `vo/` | 多 `app` / `formatVersion` / `exportedAt` |

## 三、为什么不"一个类走天下"

### 3.1 DTO 和 VO 复用的问题

最省事的写法是 `CanvasNodeDTO` 同时当入参和出参。问题：

1. **校验注解污染出参**：Swagger 会把 `@NotBlank` 显示成"必填"，但出参不存在"必填"概念。
2. **演化方向相反**：入参会越收越紧（新加字段校验），出参会越裁越少（不暴露内部字段）。
3. **一旦复用，加字段就是双向影响**：给入参加个内部字段，出参立刻就泄漏了。

`UserVO` 里那句注释就是这个意思——它刻意不含 `passwordHash` / `deleted` / `status`。

### 3.2 Entity 为什么不能直接返回

除了上面说的"泄漏内部字段"，还有两个具体问题：

- 实体里 `metadata` 是 `String`，直接返回会让前端拿到一个**转义的 JSON 字符串**（`"{\"prompt\":\"日落\"}"`），前端还得再 `JSON.parse` 一次；
- 实体里 `posX` / `posY` 是平铺的，而前端类型是 `position: {x, y}`。

所以 `toNodeVO` 做两件事：`metadata` 反序列化成 `JsonNode`，坐标组装成嵌套对象。

### 3.3 `metadata` 为什么在 DTO/VO 用 `JsonNode`

```java
@Schema(description = "节点扩展数据，结构随类型变化，后端原样保存")
JsonNode metadata
```

- 用 `Object` 会变成 `LinkedHashMap`，数字可能变 `Double`；
- 用 `Map<String, Object>` 同样有数字类型问题；
- 用 `JsonNode` 保留原始类型（`1` 还是 `1`，不会变 `1.0`），且可以原样序列化回去。

实测回读：

```json
{"tags": ["a", "b"], "count": 3, "nested": {"k": 1.5}, "prompt": "日落"}
```

`count` 是整数、`k` 是小数，都保持原样。

### 3.4 嵌套 `Position` 用内部 record

```java
public record CanvasNodeVO(..., Position position, ...) {
    @Schema(description = "节点坐标")
    public record Position(Double x, Double y) {
    }
}
```

不单独建 `PositionVO` 文件，因为：
- 它只被这一个 VO 使用；
- 嵌套 record 天然表达了"这是节点的一部分"；
- 少一个文件，命名空间更干净。

DTO 里也有一份独立的 `CanvasNodeDTO.Position`——**刻意不共用**。共用的话，给入参加 `@NotNull` 会让出参也带上校验语义。

## 四、转换代码放在哪

```java
private CanvasNodeVO toNodeVO(CanvasNode node) {
    return new CanvasNodeVO(
            node.getNodeKey(),          // 注意：id 用 nodeKey，不是数据库主键
            node.getType(),
            node.getTitle(),
            new CanvasNodeVO.Position(node.getPosX(), node.getPosY()),
            node.getWidth(),
            node.getHeight(),
            readMetadata(node.getMetadata())
    );
}
```

三个原则：

1. **转换写在 Service 里**，不写在 Controller——Controller 只接参、调 Service、包响应；
2. **不用 MapStruct / BeanUtils.copyProperties**：字段名和结构都不一样（`nodeKey → id`、`posX/posY → position`），反射拷贝解决不了，反而多一个依赖；
3. **转换方法私有**，外部只能拿到 VO，拿不到实体。

### 为什么 `id` 映射的是 `nodeKey`

```java
node.getNodeKey()   // 前端原始 id，如 n_0
```

数据库主键每次保存快照都会变，**绝不能让前端看到它**，否则前端会误以为节点身份变了。VO 里的 `id` 语义是"前端节点 id"，和表主键无关。这是全量替换方案能成立的前提。

## 五、`ProjectExportVO` 为什么又是新的一层

```java
public record ProjectExportVO(
        String app,              // "infinite-canvas"
        Integer formatVersion,   // 1
        LocalDateTime exportedAt,
        String title,
        ...
) {
```

不复用 `ProjectDetailVO` 的原因：**导出文件是对外契约，内部 VO 加字段不该改变导出格式**。

举例：以后 `ProjectDetailVO` 加了 `lastEditorId`，如果导出直接复用它，导出文件里就会多出这个内部字段——所有解析这个文件的地方都得兼容。多写一个类，换一个稳定的对外格式，值得。

详见 10 篇。

## 六、设计决策

| 决策 | 选了 | 替代 | 原因 |
| --- | --- | --- | --- |
| DTO / VO | 分开 | 复用 | 校验语义、演化方向不同 |
| Entity 出参 | 禁止 | 直接用 | 泄漏字段 + metadata 形态不对 |
| 转换方式 | 手写 | MapStruct | 字段结构不一致，拷贝工具帮不上 |
| metadata 类型 | `JsonNode` | `Map` / `Object` | 保留数字类型 |
| 嵌套结构 | 内部 record | 独立文件 | 只服务一个 VO |

## 七、面试考察点

### 基础题

**Q1：DTO、VO、Entity 分别是什么？**

- **Entity**：数据库行的映射，字段和表一一对应；
- **DTO**（Data Transfer Object）：入参，承载"前端要传什么"，带校验；
- **VO**（View Object）：出参，承载"前端需要看什么"，做过裁剪。

**Q2：为什么不直接用 Entity 接请求参数？**

① 会暴露不该改的字段（`userId`、`version`、`deleted`），前端可以传任意值；② 校验注解会绑在实体上，换个场景就要改实体；③ 实体结构变了会连带影响接口。

这是"参数覆盖攻击"的经典来源：如果 `PUT /users/me` 直接用 `User` 接参数，前端就能传 `status: 1` 给自己提权。

### 进阶题

**Q3：转换逻辑放 Service 还是 Controller？**

Service。Controller 的职责边界是"HTTP 适配"，如果它开始做结构转换，说明业务逻辑在往上漏。而且同一段业务可能被定时任务、消息消费者调用，那些入口没有 Controller。

**Q4：`JsonNode` 和 `Map<String,Object>` 有什么区别？**

`JsonNode` 保留 JSON 的类型系统（整型/浮点/数组/对象），`Map` 走 Jackson 默认反序列化会把所有数字变成 `Integer` 或 `Double`，且嵌套结构变成 `Map`，回写时可能变形。透传场景 `JsonNode` 更安全。

**Q5：为什么不用 MapStruct？**

MapStruct 擅长"字段名相同、结构相同"的映射。本项目的转换包含重命名（`nodeKey→id`）、结构重组（`posX/posY→position`）、类型转换（`String→JsonNode`），这些都要写自定义方法，收益不如直接手写。

### 陷阱题

**Q6：VO 里不返回 null 字段是怎么回事？**

全局 `spring.jackson.default-property-inclusion: non_null`。所以 `avatarUrl` 为 null 时，响应里**没有这个 key**（阶段 1 实测过）。前端要写 `data.avatarUrl ?? ''`。

这条同样影响本阶段：`metadata` 为 null 时节点对象里就没有 `metadata` 字段。

## 八、常见坑

| 坑 | 现象 | 处理 |
| --- | --- | --- |
| Entity 直接返回 | 泄漏 `userId`/`deleted` | 一律转 VO |
| VO 里的 id 用主键 | 前端节点身份错乱 | 用 `nodeKey` |
| `metadata` 返回字符串 | 前端要多 parse 一次 | 转 `JsonNode` |
| 入参用 Entity 接 | 参数覆盖攻击 | 独立 DTO |
| 转换写在 Controller | 逻辑复用不了 | 放 Service |

## 九、自检清单

- [ ] 说得出 DTO/VO/Entity 各自的职责边界
- [ ] 能解释为什么 VO 的 id 必须是 `nodeKey`
- [ ] 知道 `JsonNode` 相比 `Map` 的优势
- [ ] 能说出"入参用 Entity"会导致什么安全漏洞
- [ ] 知道全局 `non_null` 对前端取值的影响
