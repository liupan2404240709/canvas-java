# 07 · Redis 配置

> 对应源码：`config/RedisConfig.java`
> 配套配置：`application-dev.yml` 的 `spring.data.redis` 段、`pom.xml` 的 `commons-pool2`

## 一、一句话定位

自定义一个 `RedisTemplate`，把 key 存成**可读字符串**、value 存成**JSON**。

不写这个类也能跑（Boot 会给你一个默认的），但默认的 key 是二进制乱码——`redis-cli` 里 `KEYS *` 看到一片 `\xac\xed\x00\x05t\x00`，缓存出问题时完全没法排查。

## 二、完整代码

### RedisConfig.java

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
```

### 配套配置

```yaml
spring:
  data:
    redis:
      host: 127.0.0.1
      port: 6380          # 容器映射端口，避开本机 Memurai 的 6379
      database: 0
      timeout: 3s
      lettuce:
        pool:
          max-active: 16
          max-idle: 8
          min-idle: 2
          max-wait: 1s
```

## 三、逐段拆解

### 3.1 默认的 RedisTemplate 有什么问题

Spring Boot 的 `RedisAutoConfiguration` 会给两个 Bean：

```java
@Bean
@ConditionalOnMissingBean(name = "redisTemplate")
public RedisTemplate<Object, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<Object, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    return template;   // ← 没设序列化器
}

@Bean
@ConditionalOnMissingBean
public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) { ... }
```

`RedisTemplate` 不设序列化器时，默认用 `JdkSerializationRedisSerializer`。

**实际存进去长这样**：

```
127.0.0.1:6379> KEYS *
1) "\xac\xed\x00\x05t\x00\x0fcanvas:snapshot"
127.0.0.1:6379> GET "\xac\xed\x00\x05t\x00\x0fcanvas:snapshot"
"\xac\xed\x00\x05sr\x00\x13java.util.HashMap..."
```

**四个具体问题**：

| 问题 | 后果 |
| --- | --- |
| key 是二进制 | `KEYS canvas:*` 匹配不到，`redis-cli` 里没法按前缀查 |
| value 是 Java 序列化 | 其他语言（Python 脚本、Go 服务）读不了 |
| 类名写进了字节流 | 改了包名或类名，老数据反序列化直接抛 `ClassNotFoundException` |
| 体积膨胀 | Java 序列化比 JSON 大 30%~50% |

**为什么默认要用 JDK 序列化**：因为它「什么都能序列化」——不需要无参构造、不需要 getter，`Serializable` 就行。是「能用」的选择，不是「好用」的选择。

### 3.2 四种序列化器怎么选

| 序列化器 | 存储形态 | 优点 | 缺点 | 适用 |
| --- | --- | --- | --- | --- |
| `JdkSerializationRedisSerializer` | 二进制 | 什么都能存 | 不可读、不跨语言、体积大 | 不推荐 |
| `StringRedisSerializer` | 纯字符串 | 最短、可读 | 只能存 String | **key**、计数器 |
| `Jackson2JsonRedisSerializer<T>` | JSON | 可读、体积小 | **构造时需指定类型**，泛型字段会退化成 `LinkedHashMap` | 固定类型的 value |
| **`GenericJackson2JsonRedisSerializer`**（当前） | JSON **带类型信息** | 能还原具体类型，泛型也没问题 | 体积稍大（多了 `@class` 字段） | **通用 value** |

**当前的选择**：key 用 `StringRedisSerializer`，value 用 `GenericJackson2JsonRedisSerializer`。

存进去长这样：

```
127.0.0.1:6379> KEYS *
1) "canvas:snapshot:1839201"
127.0.0.1:6379> GET canvas:snapshot:1839201
"{\"@class\":\"com.infinitecanvas.canvas.vo.CanvasSnapshotVO\",\"id\":\"1839201\",\"nodes\":[...]}"
```

`@class` 字段就是 `GenericJackson2JsonRedisSerializer` 自动加的类型信息，反序列化时靠它还原成原来的类。

**它的代价**：如果类被重命名或移动包，老数据反序列化会失败。所以**缓存数据应该是「可丢弃」的**——真出了问题，清缓存重建即可，不像数据库那样必须兼容。

### 3.3 为什么四个 set 方法都要调

```java
template.setKeySerializer(keySerializer);        // 普通 key
template.setHashKeySerializer(keySerializer);    // hash 的 field
template.setValueSerializer(valueSerializer);    // 普通 value
template.setHashValueSerializer(valueSerializer);// hash 的 value
```

Redis 有 5 种基本数据结构，`RedisTemplate` 把「序列化策略」拆成了两组：

| 结构 | key 用什么 | value 用什么 |
| --- | --- | --- |
| String | `keySerializer` | `valueSerializer` |
| List / Set / ZSet | `keySerializer` | `valueSerializer`（元素） |
| **Hash** | `keySerializer` | **`hashKeySerializer`（field）** + **`hashValueSerializer`（value）** |

**只设 `keySerializer` 和 `valueSerializer`，用 `opsForHash()` 时会怎样**：

```java
redisTemplate.opsForHash().put("canvas:meta", "title", "我的画布");
```

`field` 和 `value` 还是用默认的 JDK 序列化——**同一个 Template 里两套序列化策略**，排查时更加困惑。这是很常见的漏配。

### 3.4 `afterPropertiesSet()` 为什么必须调

```java
template.afterPropertiesSet();
```

这行是 `InitializingBean` 接口的方法。`RedisTemplate` 在 `afterPropertiesSet()` 里做**默认序列化器的兜底赋值**：

```java
// RedisTemplate 源码（简化）
@Override
public void afterPropertiesSet() {
    super.afterPropertiesSet();
    boolean defaultUsed = false;
    if (defaultSerializer == null) {
        defaultSerializer = new JdkSerializationRedisSerializer(
                classLoader != null ? classLoader : this.getClass().getClassLoader());
    }
    if (enableDefaultSerializer) {
        if (keySerializer == null) { keySerializer = defaultSerializer; defaultUsed = true; }
        if (valueSerializer == null) { valueSerializer = defaultSerializer; defaultUsed = true; }
        // ... 同样处理 hashKey / hashValue
    }
    if (defaultUsed) { /* 打一条 debug 日志 */ }
}
```

**如果你手动 new 了 `RedisTemplate` 但没调这个方法**：Bean 是手工创建的，Spring 不会自动调 `afterPropertiesSet()`（只有容器管理的 Bean 才会走生命周期回调）。结果是某些序列化器为 null，运行时抛 NPE。

**正确理解**：不是「调了才会生效」，而是「不调可能漏初始化」。用 `@Bean` 返回的对象，Spring 会调 `afterPropertiesSet()`——但显式写出来更安全，也让人一眼看出依赖初始化。

> 实际上，`@Bean` 方法返回的对象**会**被 Spring 调用 `afterPropertiesSet()`（因为它实现了 `InitializingBean`）。这里显式调用是「防御性写法 + 可读性」。

### 3.5 `StringRedisTemplate` 和 `RedisTemplate` 的关系

```java
public class StringRedisTemplate extends RedisTemplate<String, String> {
    public StringRedisTemplate() {
        setKeySerializer(RedisSerializer.string());
        setValueSerializer(RedisSerializer.string());
        setHashKeySerializer(RedisSerializer.string());
        setHashValueSerializer(RedisSerializer.string());
    }
}
```

**`StringRedisTemplate` 就是一个「四个序列化器全设成 String」的 `RedisTemplate` 子类。**

**本项目两个都在用**：

| 场景 | 用哪个 | 为什么 |
| --- | --- | --- |
| 健康检查 `PING` | `StringRedisTemplate` | 只需要发命令，不存对象 |
| 存对象（会话、快照、任务状态） | 自定义的 `RedisTemplate<String, Object>` | 要 JSON 序列化 |

**关键点**：两个 Bean **不是同一个连接**，但共享 `RedisConnectionFactory`（也就是共享连接池）。不会浪费连接。

**面试常见陷阱**：「你有两个 RedisTemplate，会不会冲突？」——不会，Bean 名字不同（`redisTemplate` 和 `stringRedisTemplate`），`@ConditionalOnMissingBean(name = "redisTemplate")` 只影响前者。

### 3.6 Lettuce 和 Jedis 的选择

Spring Boot 2.x 之后默认用 **Lettuce**。

| | Lettuce | Jedis |
| --- | --- | --- |
| 底层 | Netty，**异步 + 响应式** | 阻塞 IO，同步 |
| 线程安全 | **线程安全**，单连接可多线程共享 | 非线程安全，必须用连接池 |
| 连接数 | 少量连接即可（默认 1 个共享连接） | 每个线程一个连接 |
| 依赖 | 默认自带 | 需要额外引入并排除 Lettuce |
| 集群/哨兵支持 | 内置 | 需要额外配置 |

**为什么本项目配了连接池**：

```yaml
lettuce:
  pool:
    max-active: 16
```

Lettuce 默认**不池化**（用单个共享连接 + 多路复用），Spring Boot 里 `spring.data.redis.lettuce.pool` 配置了才启用池化。项目配池化的理由：

1. 未来会有阻塞命令（如 `BLPOP`），会占用连接
2. 事务（`MULTI/EXEC`）需要独占连接
3. 主从切换、重连时池化更稳定

**用池化就必须加 `commons-pool2` 依赖**：

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

**没加会怎样**：启动时报 `java.lang.NoClassDefFoundError: org/apache/commons/pool2/impl/GenericObjectPoolConfig`，或者 `ClassNotFoundException`。这是很经典的坑——YAML 配了 pool 但依赖没加。

**本项目 `pom.xml` 里已经加了**（第 43-46 行），所以不踩这个坑。

### 3.7 Redis 的 key 设计规范

虽然代码里还没有实际的 key，但阶段 2 开始就要定规范。本项目约定：

```
{业务}:{实体}:{id}          例：canvas:snapshot:1839201
{业务}:{实体}:{类型}:{id}    例：agent:thread:messages:abc123
{业务}:{功能}                例：auth:refresh:token
```

**要求**：

| 要求 | 原因 |
| --- | --- |
| 用 `:` 分隔（不是 `_` 或 `.`） | Redis 生态通用约定，`redis-cli` 有树状展示 |
| 全局统一前缀（`canvas:`） | 多项目共用一个 Redis 实例时能区分 |
| key 不要过长 | Redis 的 key 也占内存，`canvas:snapshot:` 比 `infinite-canvas-project-snapshot:` 好 |
| 必须设 TTL | 除了明确的永久数据，所有缓存都要 `expire`，否则内存无限增长 |
| 不用 `KEYS *` | 阻塞式命令，生产环境会卡住整个 Redis，用 `SCAN` |

**阶段 2 会用到的 key**（提前规划）：

```
canvas:snapshot:{projectId}     画布快照缓存，TTL 1h
canvas:list:{userId}:{page}     项目列表缓存，TTL 5m
```

## 四、设计决策

| 决策 | 选择 | 理由 | 替代方案与代价 |
| --- | --- | --- | --- |
| value 序列化 | `GenericJackson2JsonRedisSerializer` | 可读 + 能还原类型 | JDK 序列化——乱码；`Jackson2JsonRedisSerializer`——泛型退化 |
| key 序列化 | `StringRedisSerializer` | 可读、可前缀匹配 | JDK——`KEYS canvas:*` 失效 |
| hash 序列化 | 同 key/value 策略 | 避免同一 Template 两套策略 | 只设 key/value——hash 用 JDK 序列化 |
| 客户端 | Lettuce | Boot 默认，线程安全，异步 | Jedis——需额外配置，连接数多 |
| 连接池 | 开启 | 支持阻塞命令和事务 | 不池化——够用但扩展性差 |
| 健康检查 | `StringRedisTemplate` | 不涉及对象序列化 | 用自定义 Template——没必要 |
| 端口 | **6380**（容器） | 避开本机 Memurai 的 6379 | 6379——和本机服务冲突 |

## 五、面试考察点

### 基础题

**Q1：`RedisTemplate` 和 `StringRedisTemplate` 有什么区别？**

答题要点：
- `StringRedisTemplate` 继承 `RedisTemplate<String, String>`，把四个序列化器全设成 `StringRedisSerializer`
- `RedisTemplate` 默认用 `JdkSerializationRedisSerializer`，存的是二进制
- 存对象用 `RedisTemplate`（配 JSON 序列化），纯字符串/计数用 `StringRedisTemplate`
- 两者共享 `RedisConnectionFactory`，不额外占连接

**Q2：为什么要自定义 `RedisTemplate`？**

答题要点：
- 默认的 JDK 序列化导致 key 是二进制，`redis-cli` 里没法按前缀查
- 不可跨语言，Python/Go 读不了
- 改类名/包名会导致老数据反序列化失败
- 改成 String key + JSON value 后，可读、可跨语言、体积小

**Q3：`GenericJackson2JsonRedisSerializer` 和 `Jackson2JsonRedisSerializer` 的区别？**

答题要点：
- `Jackson2JsonRedisSerializer<T>` 构造时要指定类型，反序列化时按固定类型处理，**泛型字段会退化成 `LinkedHashMap`**
- `GenericJackson2JsonRedisSerializer` 序列化时写入 `@class` 类型信息，反序列化时靠它还原真实类型，泛型也没问题
- 代价：体积稍大，且类重命名会导致老数据反序列化失败

### 进阶题

**Q4：四个序列化器的方法分别管什么？只配两个会怎样？**

答题要点：
- `keySerializer` / `valueSerializer` 管 String/List/Set/ZSet
- `hashKeySerializer` / `hashValueSerializer` 管 Hash 的 field 和 value
- 只配前者，用 `opsForHash()` 时 field 和 value 还是 JDK 序列化
- 结果：同一个 Template 里两套序列化策略，排查极其困惑

**Q5：Lettuce 和 Jedis 怎么选？**

答题要点：
- Lettuce：Netty 实现，异步/响应式，**线程安全**，单连接多路复用，Boot 2.x 默认
- Jedis：阻塞 IO，非线程安全，必须池化，每个线程一个连接
- 高并发下 Lettuce 连接数少、性能好；Jedis API 更直观但不适合大并发
- Spring Boot 3 用 Lettuce

**Q6：`spring.data.redis.lettuce.pool` 配了但启动报 `NoClassDefFoundError`，为什么？**

答题要点：
- 缺 `commons-pool2` 依赖
- Lettuce 默认不池化，配了 pool 才需要 commons-pool2
- 加依赖即可：`org.apache.commons:commons-pool2`

**Q7：Redis 缓存和数据库的一致性怎么保证？**

答题要点（这道题现在先建立框架，阶段 2 会真正实现）：
- **Cache Aside**（最常用）：读时 miss 查库回填；写时**先更新数据库，再删缓存**
- 为什么是「删」不是「更新」：并发下更新缓存的顺序不可控，删更安全（下次读会回源）
- 延迟双删：更新库 → 删缓存 → 延迟 500ms 再删一次，应对「读请求在删缓存后回填了旧值」
- 强一致要加分布式锁或走 binlog 订阅（Canal）

### 陷阱题

**Q8：`RedisTemplate` 手动 new 出来没调 `afterPropertiesSet()` 会怎样？**

答题要点：
- `afterPropertiesSet()` 里会做「默认序列化器兜底赋值」
- 不调的话某些序列化器可能为 null，运行时报 NPE
- `@Bean` 返回的对象其实 Spring 会调（实现了 `InitializingBean`），显式调用是防御性写法

**Q9：缓存 key 用 `KEYS canvas:*` 查会有什么问题？**

答题要点：
- `KEYS` 是**阻塞式**命令，要遍历整个 keyspace
- 数据量大时（百万级）会阻塞 Redis 主线程几秒，期间所有请求卡住
- 生产禁用，改用 `SCAN cursor MATCH pattern COUNT 1000` 增量遍历

**Q10：Redis 存的对象类改了包名，会怎样？**

答题要点：
- `GenericJackson2JsonRedisSerializer` 靠 `@class` 字段找类
- 找不到类会抛 `ClassNotFoundException` / `SerializationException`
- 缓存应该是可丢弃的：清掉重建即可
- 需要兼容时配置 `TypeResolver` 或自定义 `ObjectMapper` 的类型映射

## 六、常见坑

| 坑 | 现象 | 原因 | 处理 |
| --- | --- | --- | --- |
| 不配序列化器 | key 是二进制乱码 | 默认 JDK 序列化 | 自定义 Template |
| 只配 key/value | Hash 的 field 是乱码 | 漏配 hash 两个 | 四个都配 |
| 配了 pool 没加依赖 | 启动 `NoClassDefFoundError` | 缺 `commons-pool2` | 加依赖 |
| 端口写 6379 | 连到了本机 Memurai | 容器映射到 6380 | 改 `application-dev.yml` |
| 用 `KEYS *` 查缓存 | Redis 卡住 | 阻塞命令 | 用 `SCAN` |
| key 不设 TTL | 内存持续增长 | 忘了 `expire` | 所有缓存必须设过期 |
| 用 `Jackson2JsonRedisSerializer` 存泛型 | 取出来变成 `LinkedHashMap`，强转 `ClassCastException` | 泛型擦除 | 用 `GenericJackson2JsonRedisSerializer` |

## 七、自检清单

- [ ] 能说出默认 `RedisTemplate` 存出来的数据长什么样，以及四个具体问题
- [ ] 知道四个序列化器方法分别管什么
- [ ] 能对比 `GenericJackson2Json` 和 `Jackson2Json` 的差异
- [ ] 说得清 Lettuce 和 Jedis 的 5 个差异
- [ ] 知道配连接池必须加 `commons-pool2`
- [ ] 能设计出规范的 Redis key
- [ ] 知道 `KEYS` 为什么不能在线上用
- [ ] 能说出 Cache Aside 的写流程为什么是「删缓存」而不是「更新缓存」
