# 02　RestClient 与超时配置

> 代码位置：`config/PromptHttpConfig.java`、`infra/prompt/PromptFetcher.java`、`infra/prompt/PromptProperties.java`

## 1. 为什么单独建一个 HTTP 客户端

项目里已经有 `spring-boot-starter-web` 自动配置的 `RestClient.Builder`。直接用它会怎样？

`RestClient.Builder` 是**原型 bean**，每次注入都是新的。自动配置的 `ClientHttpRequestFactory` 来自 `ClientHttpRequestFactorySettings`，默认**不设超时**（`HttpURLConnection` 的默认是 0 = 无限等待）。

对业务接口来说，"读超时"由 Tomcat/网关兜底；但对**出站请求**：

- 目标域名不响应 → 这个线程一直卡在 `read()`；
- Tomcat 默认 200 个线程，7 个源 × 每个用户点一次同步，很快就打满；
- 打满之后所有接口（包括登录）都 503。

所以单独建 `promptRestClient`：

```java
@Bean
public RestClient promptRestClient(RestClient.Builder builder, PromptProperties properties) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());  // 5s
    factory.setReadTimeout((int) properties.getReadTimeout().toMillis());        // 20s
    return builder.requestFactory(factory).build();
}
```

**为什么单独一个 bean 而不是改全局**：业务接口的超时应该由容器/网关决定；把全局客户端改成 5 秒，未来某个慢查询接口会莫名 500。**不同用途的客户端用不同超时**，这是原则。

## 2. Timeout 的两种含义

`SimpleClientHttpRequestFactory` 底层是 `HttpURLConnection`：

| 设置 | 对应 | 什么时候触发 |
| --- | --- | --- |
| `connectTimeout` | `URLConnection.setConnectTimeout` | TCP 三次握手（DNS + 建连）超时 |
| `readTimeout` | `URLConnection.setReadTimeout` | 建连成功后，等待数据/等待下一段数据超时 |

两个都必须设。只设 `readTimeout` 的话，DNS 解析卡住（比如域名指向一个黑洞 IP）会一直挂着。

`readTimeout` 是"两次数据到达之间的最长间隔"，不是"整个响应总时长"。所以一个大文件慢慢传，可能单次 `read` 都不超时，但总耗时很长。对提示词 JSON（MB 级）无所谓，真正的兜底是响应体上限。

## 3. 为什么不用 WebClient

`WebClient` 是响应式的，超时要用 `Mono.timeout()` 或 Reactor Netty 的 `responseTimeout`，还需要处理事件循环。

这里的场景是**同步拉一份 JSON，然后同步写库**——没有背压、没有流式消费、没有并行聚合。用 WebClient 只会让调用链多一层 `Mono`/`block()`（而 `block()` 在 WebFlux 里是禁忌，在 MVC 里也没意义）。

**选型口径**：调用链里有没有"必须响应式"的环节？没有就用 `RestClient`。

## 4. 状态码不在客户端抛异常

```java
.defaultStatusHandler(status -> false, (request, response) -> { })
```

`RestClient` 默认把 4xx/5xx 当异常抛（`HttpClientErrorException` 等）。这里显式关掉，因为 `PromptFetcher` 要在 `exchange` 里统一判断：

```java
.exchange((request, response) -> {
    int status = response.getStatusCode().value();
    if (status == 304) return new FetchResult("", etag, lastModified, true);
    if (status < 200 || status >= 300) throw new PromptSyncException("上游返回 HTTP " + status);
    byte[] body = readLimited(response.getBody());
    ...
});
```

好处：
- **304 不是错误**，但默认处理器不会把它当异常（3xx），两套逻辑混在一起容易漏；
- 404/500 的文案要写进 `last_error`（"上游返回 HTTP 404"比 `HttpClientErrorException$NotFound: 404 Not Found` 对用户友好得多）；
- 一个地方能看全所有状态码处理。

## 5. 流式限长：为什么不能先读再判断

```java
private byte[] readLimited(InputStream in) throws IOException {
    long limit = properties.getMaxResponseSize().toBytes();
    ByteArrayOutputStream out = new ByteArrayOutputStream(BUFFER_SIZE);
    byte[] buffer = new byte[BUFFER_SIZE];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
        total += read;
        if (total > limit) throw new PromptSyncException("响应体超过上限，已中断");
        out.write(buffer, 0, read);
    }
    return out.toByteArray();
}
```

错误写法：

```java
byte[] body = response.getBody().readAllBytes();   // ❌ 先全读进内存
if (body.length > limit) throw ...;                // 判断的时候内存已经吃掉了
```

一个恶意/坏掉的 URL 返回 10GB 流，第一种写法直接 OOM。第二种写法还有个变体：`Content-Length` 判断——但 `Content-Length` 可以撒谎（chunked 编码没有长度），所以**必须按实际读取的字节数累计**。

这就是阶段 3 上传时"64KB 循环里同时累加大小 + 算摘要 + 写临时文件"的同一个思路：**流式处理 + 硬上限**。

## 6. User-Agent 不是可选项

```java
headers.set(HttpHeaders.USER_AGENT, "canvas-server-prompt-sync/1.0");
```

- GitHub raw 对空 UA 的请求会更严格（有时直接 403）；
- 出问题时，对方运维能在日志里认出这是谁在请求（"能联系上的爬虫"比"匿名请求"待遇好）；
- 便于在自己的日志/监控里区分来源。

## 7. 异常信息要"翻译"一层

```java
} catch (Exception e) {
    throw new PromptSyncException("请求失败：" + rootMessage(e), e);
}
```

`rootMessage` 一路 `getCause()` 找到最内层，取 message（为空则取类名）。

原因：`RestClient` 的异常链经常是 `ResourceAccessException → ConnectException → ...`，直接把 `e.getMessage()` 写进 `last_error` 会得到一长串包名，用户看不懂，还占满 500 字符的字段。

对用户展示"请求失败：Connection timed out"，完整堆栈进日志——这是**同一件事的两种表述**，不是重复。

---

## 面试考察点

**Q1：RestClient 和 WebClient 怎么选？**
看调用链是否需要响应式。纯同步拉取（本场景）用 RestClient，代码线性、异常直观；需要流式转发/高并发聚合（比如后面阶段的 SSE 推流）才用 WebClient。

**Q2：连接超时和读超时的区别？只设一个会怎样？**
连接超时管 TCP 握手（含 DNS），读超时管建连后等数据。只设读超时：DNS 卡住会一直挂着；只设连接超时：连上之后服务端不返回数据，线程一样卡死。

**Q3：为什么不用 `readAllBytes()` 再判断大小？**
内存已经被吃掉了，判断失去意义。必须边读边累计，超限立刻中断。和上传的流式处理是同一个原则。

**Q4：为什么要把 4xx/5xx 排除在默认异常处理之外？**
因为要把状态码翻译成用户能看懂的一句话写进 `last_error`（"上游返回 HTTP 404"），并且 304 要单独走"未修改"分支。默认异常处理器会让这些信息丢失。

**Q5：`defaultStatusHandler(status -> false, ...)` 会不会让真正的错误被吞掉？**
不会。状态码判断移到了 `exchange` 里，非 2xx 一律转成 `PromptSyncException`，再由上层写进 `last_error` 并返回 `success=false`。只是"在哪一层判断"换了位置，不是"不判断"。

---

## 自检清单

- [ ] 能说出不设超时的两个后果
- [ ] 能解释 `connectTimeout` 与 `readTimeout` 的分工
- [ ] 能写出流式限长的循环，并说明为什么不能先读全量
- [ ] 能解释为什么要单独建 `promptRestClient` bean
- [ ] 知道 `last_error` 里应该放"翻译后"的信息，堆栈进日志
