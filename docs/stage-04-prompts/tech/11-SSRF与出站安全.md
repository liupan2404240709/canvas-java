# 11　SSRF 防护与出站请求安全

> 代码位置：`infra/prompt/PromptUrls.java`、`PromptFetcher#fetch`、`PromptProperties#allowPrivateHost`

## 1. 这个功能的本质风险

"用户可以填一个 URL，服务器去请求它" —— 这就是 SSRF（Server-Side Request Forgery）的标准形态。

攻击者不需要任何技巧，只要新增一个源：

```
http://169.254.169.254/latest/meta-data/iam/security-credentials/   ← 云厂商元数据（能拿到临时凭证）
http://127.0.0.1:6379/                                              ← 内网 Redis
http://10.0.0.5:8080/admin/shutdown                                 ← 内网管理接口
file:///etc/passwd                                                  ← 本地文件
```

响应内容虽然会经过"JSON 数组解析"（大部分内网响应会解析失败），但：

- **错误信息里会带上状态码和原因**（"上游返回 HTTP 200" vs "连接被拒绝"），这本身就能用来探测内网端口；
- 有些内网接口真的返回 JSON 数组（比如某些注册中心、监控接口）；
- 请求本身就可能产生副作用（`GET /admin/delete?id=1` —— 很多内网接口不区分方法语义）。

## 2. 三层防护

### 2.1 协议白名单

```java
String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
if (!scheme.equals("http") && !scheme.equals("https")) return null;
if (uri.getHost() == null || uri.getHost().isBlank()) return null;
```

只允许 `http` / `https`。不拦的话：

| 协议 | 后果 |
| --- | --- |
| `file:///etc/passwd` | 直接读本地文件（如果客户端支持） |
| `ftp://` | 触发 FTP 连接，可能被用来扫描内网 FTP |
| `jar://` / `gopher://` | 经典 SSRF 利用链的一部分 |
| `http://user:pass@host` | 凭据泄露（`getHost` 仍能正确解析，但值得注意） |

`getHost()` 为空的也要拒绝：`http:///path`、`http://[::1`（非法 IPv6）这类畸形 URL 会让后续逻辑拿到 null。

### 2.2 私网地址检查

```java
public static boolean hostAllowed(URI uri, boolean allowPrivate) {
    if (allowPrivate) return true;
    for (InetAddress address : InetAddress.getAllByName(host)) {
        if (address.isLoopbackAddress()        // 127.0.0.0/8, ::1
                || address.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16
                || address.isLinkLocalAddress()  // 169.254/16（云元数据！）, fe80::
                || address.isAnyLocalAddress()   // 0.0.0.0
                || address.isMulticastAddress()) {
            return false;
        }
    }
    return true;
}
```

**为什么必须解析 IP，不能只匹配字符串**：

| 绕过写法 | 字符串匹配 | IP 解析 |
| --- | --- | --- |
| `http://0x7f000001/` | 看不出来 | `InetAddress` 解析成 127.0.0.1 ✅ |
| `http://2130706433/`（十进制） | 看不出来 | 解析成 127.0.0.1 ✅ |
| `http://[::ffff:127.0.0.1]/` | 看不出来 | 解析成 IPv4 映射地址 ✅ |
| `http://127.1/`（省略写法） | 前缀匹配可能漏 | 解析成 127.0.0.1 ✅ |
| `http://evil.com`（DNS 指向 127.0.0.1） | 看起来是正常域名 | 解析后发现 ✅ |

`getAllByName` 返回**所有** A/AAAA 记录，所以要遍历全部，任意一个命中私网就拒绝（防止"一个公网 + 一个内网"的混合作答）。

### 2.3 响应体上限

```java
while ((read = in.read(buffer)) != -1) {
    total += read;
    if (total > limit) throw new PromptSyncException("响应体超过上限，已中断");
    out.write(buffer, 0, read);
}
```

为什么这属于"安全"而不只是"健壮性"：一个恶意 URL 返回无限流（`/dev/zero` 风格），会直接把堆打爆，服务不可用。这是**可用性攻击**的一种。

不能用 `Content-Length` 判断：chunked 编码没有长度，而且头部可以撒谎。

## 3. 为什么默认放开私网（`allow-private-host: true`）

```yaml
app:
  prompt:
    # 安全开关：允许请求内网/回环地址。本地开发要 true（e2e 用本机的样例 JSON 做同步），
    # 一旦这个服务对外暴露就必须改成 false，否则等于给别人开了一个内网探测接口
    allow-private-host: true
```

这是**有意的开发便利 vs 生产安全的取舍**：

- e2e 脚本需要请求 `http://127.0.0.1:8081/api/demo/prompts-sample.json`；如果默认拒绝私网，本地就没法离线验证整条链路；
- 关掉的话，本地开发只能用真实外网地址（国内还不一定通）。

缓解措施：
- 配置项名字直白（`allow-private-host`），注释里明确写了"生产必须 false"；
- 写在 `application.yml` 的显眼位置，不是藏在代码常量里；
- `docs/database.md`、本阶段 README、tech/11 三处都提到。

> 生产环境的正确做法是**默认 false，开发 profile 里覆盖成 true**：
> `application-dev.yml` 写 `allow-private-host: true`，`application.yml` 写 false。
> 当前为了本地能跑，主配置里写的是 true——这是已知的、写在文档里的偏差。

## 4. 已知局限：DNS Rebinding

```
攻击者控制的 DNS：
  第 1 次解析 evil.com → 1.2.3.4（公网，检查通过）
  第 2 次解析 evil.com → 127.0.0.1（真正连接时）
```

我们的检查是"解析一次 → 检查 → 然后交给 HTTP 客户端再解析一次"。两次解析之间可以变。

彻底解决需要**在连接层固定解析结果**：
- 自己实现 `HttpURLConnection` 的 `Proxy`/`SocketFactory`，把 IP 钉死；
- 或者走正向代理（所有出站流量经代理，由代理做策略）；
- 或者在网络层做 egress firewall。

这属于网关/基础设施的职责。**在应用层做到这一步，成本和收益不成比例**，所以这里明确记录局限，而不是假装解决了。

## 5. 其他相关防护

| 项 | 做法 | 为什么 |
| --- | --- | --- |
| 协议 | http/https 白名单 | 见 2.1 |
| 主机 | 私网检查（可配） | 见 2.2 |
| 响应大小 | 8MB 流式上限 | 见 2.3 |
| 建连超时 | 5s | 防慢速连接占用线程（Slowloris 变体） |
| 读超时 | 20s | 防服务端不返回 |
| 重定向 | 默认跟随（`HttpURLConnection`） | ⚠️ 见下 |
| 请求头 | 固定 UA / Accept，不透传用户的 header | 防止用户通过 header 注入（如 `Host`） |

### 重定向的风险

`HttpURLConnection` 对 GET 默认 `followRedirects = true`（同协议内）。这意味着：

```
用户填 https://evil.com/r  →  302 Location: http://127.0.0.1:6379/
```

内网检查只看**最初那个 URL**，重定向之后的目标没有检查——这是一个**真实的绕过点**。

当前实现没有关闭重定向。如果要补，最直接的方式：

```java
factory.setFollowRedirects(false);   // 或者自己处理 3xx 并重新校验
// 或者在 exchange 里判断 3xx，取出 Location 后重新走 PromptUrls.hostAllowed
```

**为什么现在没做**：`SimpleClientHttpRequestFactory` 的 `setFollowRedirects(false)` 会让所有重定向变成"非 2xx 状态码"，从而被当成失败——包括 GitHub raw 的正常 302（它会重定向到 CDN）。要做对必须实现"手动跟随 + 每跳校验"，那是十几行额外逻辑。

记录在这里，作为已知缺口，比装作没有更诚实。

## 6. 拿到内容之后还有一层

即使请求成功、内容也拿到了，仍然不能直接信任：

- 解析必须容错（[03](./03-第三方JSON归一化.md)）；
- 所有文本入库前按列宽截断（防止超长导致整批失败）；
- URL 字段补全后仍然是"外部地址"，前端渲染时要考虑（当前直接放进 `<img src>`，属于前端的事）；
- **不执行任何内容**：不反序列化成对象（不用 `enableDefaultTyping`），不解析 HTML/JS，只当纯文本。

最后一条是根本：**"永不反序列化成可执行对象"**。Jackson 的默认类型信息（`@class`）是历史上多个 RCE 漏洞的源头，这里只用 `readTree` + `asText()`，不打开多态类型。

---

## 面试考察点

**Q1：什么是 SSRF？这个功能为什么有这个风险？**
服务端被诱导去请求攻击者指定的地址。本功能让用户填 URL，服务器去请求，天然具备这个入口。经典目标是云元数据（169.254.169.254）、内网管理接口、本地文件。

**Q2：为什么不能只做字符串匹配（比如判断是否以 `127.` 开头）？**
IP 有很多等价写法：`0x7f000001`、`2130706433`、`127.1`、`[::ffff:127.0.0.1]`，还有域名解析后指向内网。必须先解析成 `InetAddress` 再判断类型。

**Q3：`isLinkLocalAddress()` 为什么重要？**
169.254.0.0/16 是链路本地地址，云厂商的元数据服务（AWS/GCP/阿里云的 169.254.169.254）就在这里，是 SSRF 最有价值的目标。

**Q4：响应体上限为什么必须在读取过程中判断？**
`readAllBytes()` 之后内存已经被占用。`Content-Length` 不可信（chunked 无长度、头部可伪造）。必须边读边累计、超限中断。

**Q5：DNS Rebinding 是什么？应用层能完全防住吗？**
第一次解析返回公网 IP 通过检查，连接时 DNS 返回内网 IP。应用层的"解析-检查-再连接"天然有窗口。要彻底解决需要在连接层固定 IP 或做网络层出口策略，属于基础设施职责。

**Q6：为什么允许重定向是个风险？怎么修？**
重定向目标不会经过我们的地址校验，可以用来绕过私网检查。修法：关闭自动跟随，手动处理 3xx 并对每一跳重新校验（`PromptUrls.hostAllowed`）。

---

## 自检清单

- [ ] 能说出 SSRF 的 3 个经典目标
- [ ] 能列举至少 4 种 IP 等价写法，并说明为什么字符串匹配无效
- [ ] 知道 `isLinkLocalAddress` 与云元数据的关系
- [ ] 能解释响应体上限为什么必须流式判断
- [ ] 能说出 `allow-private-host` 的默认值和生产要求
- [ ] 知道重定向绕过和 DNS rebinding 是本实现的已知局限
- [ ] 知道"不打开 Jackson 多态类型"是防 RCE 的底线
