# 03 Base URL 归一化与端点拼接

> 对应代码：`infra/channel/ChannelApiUrls.java`（93 行）

## 一、这块看起来只是拼字符串

实际上它是整个阶段**最容易出隐性 bug** 的地方。原因：没有统一的"根地址"标准。

各家文档给的 base_url 形态完全不一样：

| 厂商 | 控制台给的 base_url | 拉模型实际要打 |
| --- | --- | --- |
| 阿里百炼 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `.../compatible-mode/v1/models` |
| 火山方舟 | `https://ark.cn-beijing.volces.com/api/v3` | `.../api/v3/models` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `.../api/paas/v4/models` |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `.../v1/models` |
| DeepSeek | `https://api.deepseek.com` | `https://api.deepseek.com/v1/models` |
| 本地 Ollama | `http://127.0.0.1:11434/v1` | `.../v1/models` |
| Gemini | `https://generativelanguage.googleapis.com` | `.../v1beta/models` |

注意 DeepSeek 那一行：**官方明确说 base_url 不需要 `/v1`**，但模型列表接口在 `/v1/models`。
如果无脑 `baseUrl + "/models"`，DeepSeek 会 404；如果无脑补 `/v1`，百炼会变成 `/v1/v1/models`。

---

## 二、规则：只看"最后一段是不是版本号"

```java
private static final Pattern VERSION_TAIL = Pattern.compile(".*/v\\d+[a-z]*$", Pattern.CASE_INSENSITIVE);

public static String modelsUrl(String baseUrl, String apiFormat) {
    String base = normalize(baseUrl);
    if ("gemini".equalsIgnoreCase(apiFormat)) {
        return VERSION_TAIL.matcher(base).matches() ? base + "/models" : base + "/v1beta/models";
    }
    return VERSION_TAIL.matcher(base).matches() ? base + "/models" : base + "/v1/models";
}
```

`v\d+[a-z]*` 这个正则覆盖了：

| 末段 | 匹配 | 结果 |
| --- | --- | --- |
| `v1` | ✅ | `+ /models` |
| `v3` | ✅ | `+ /models` |
| `v4` | ✅ | `+ /models` |
| `v1beta` | ✅ | `+ /models` |
| （无版本段） | ❌ | `+ /v1/models` |

**为什么不需要给 `api/v3`、`paas/v4` 单独写分支**：正则只认最后一段。
`/api/paas/v4` 的最后一段是 `v4`，`/api/v3` 的最后一段是 `v3`，都命中同一个模式。

这条规则的价值是**一条正则覆盖所有厂商**，包括未来新出现的厂商——
只要它遵循"版本段在末尾"这个事实上的共识。

### 2.1 否决的方案：让用户勾选"我的地址是否含版本号"

把可推导的事实交给用户填，就是给自己制造配置错误。用户不知道什么叫"版本段"，
他只想把控制台里那串地址粘进来。**能自动判断的，绝不问用户。**

### 2.2 否决的方案：给每家厂商写一条 if

```java
// 反面示例
if (baseUrl.contains("dashscope")) return baseUrl + "/models";
if (baseUrl.contains("volces")) return baseUrl + "/models";
if (baseUrl.contains("deepseek")) return baseUrl + "/v1/models";
```

坏处：①新厂商要改代码发版；②用户自建网关（vLLM、One-API、New-API）完全不在名单里；
③`deepseek` 这个词出现在任何自建地址里都会误命中。

---

## 三、`normalize()` 做了四件事

```java
public static String normalize(String raw) {
    URI uri = new URI(raw.trim());
    // 1. 协议白名单
    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return null;
    // 2. 必须有主机名
    if (uri.getHost() == null || uri.getHost().isBlank()) return null;
    // 3. 去掉锚点
    if (hash >= 0) normalized = normalized.substring(0, hash);
    // 4. 去掉末尾所有斜杠
    while (normalized.endsWith("/")) normalized = ...;
}
```

| 步骤 | 为什么 |
| --- | --- |
| 协议白名单 | 不拦的话 `file:///etc/passwd`、`ftp://` 都会进到后面的 HTTP 客户端 |
| 必须有 host | `http:///v1` 这种 URI 能解析成功但 host 为空，发出去会抛奇怪的异常 |
| 去锚点 | `#fragment` 对服务端毫无意义，留着还可能干扰拼接（`.../v1#x` + `/models`） |
| 去末尾斜杠 | 否则会拼出 `.../v1//models`。有些网关会 404，有些会 301，排查起来很浪费时间 |

注意 `normalize` **不抛异常，返回 null**。这是刻意的：它是一个纯函数，
"合法与否"的判断交给调用方决定报什么错（创建时报 40050，探测时返回 `ok=false`）。

---

## 四、`dedupeKey()`：为将来的"重复渠道"提示留的

```java
public static String dedupeKey(String baseUrl) {
    return base.toLowerCase().replaceAll("/v1$", "").replaceAll("/+$", "");
}
```

把 `https://api.x.com/v1` 和 `https://api.x.com` 归一成同一个 key。
目前**没有用它做强约束**（D4.2 解释了为什么允许两个渠道指向同一地址），
但如果将来要做"你可能重复添加了"的提示，这就是比对依据。

---

## 五、实测

用 `jshell` 直接调静态方法，10 组真实地址（不依赖数据库、不依赖网络）：

```
https://dashscope.aliyuncs.com/compatible-mode/v1/models
https://ark.cn-beijing.volces.com/api/v3/models
https://open.bigmodel.cn/api/paas/v4/models
https://api.siliconflow.cn/v1/models
https://api.deepseek.com/v1/models
http://127.0.0.1:11434/v1/models
https://generativelanguage.googleapis.com/v1beta/models
https://generativelanguage.googleapis.com/v1beta/models
https://x.com/v1/models
https://x.com/a/v1/models
```

最后两组是边界用例：`https://x.com/v1/`（末尾斜杠）和 `https://x.com/a/v1#frag`（带锚点），
都被正确归一成 `.../v1/models`。

---

## 六、面试考察点

**Q1：DeepSeek 的 base_url 不带 `/v1`，你怎么处理？**
A：拼接前判断末段是不是版本号（`v\d+[a-z]*`）。是就只补 `/models`，不是就补 `/v1/models`。
这样百炼（已带 `/compatible-mode/v1`）不会变成 `/v1/v1/models`，DeepSeek 也不会漏掉 `/v1`。

**Q2：为什么用正则而不是给每家厂商写分支？**
A：分支覆盖不了自建网关（vLLM、One-API），新厂商还要改代码发版；
而"版本段在末尾"是所有 OpenAI 兼容实现的共同点，一条正则就够。

**Q3：`normalize` 为什么要去掉锚点和末尾斜杠？**
A：锚点对服务端无意义且会干扰拼接；末尾斜杠会拼出 `//models`，有的网关 404、有的 301，
排查成本很高。这两件事都该在入口一次做掉，而不是在每个调用点防御。

**Q4：`normalize` 为什么不抛异常？**
A：它是纯函数，不应该决定"报什么错"。创建渠道时要返回 40050（带字段提示），
探测时要返回 `ok=false`（不打断流程），同一个非法输入在不同场景的处理方式不同。

**Q5：如果用户填的地址带了路径前缀，比如 `https://gw.company.com/llm/v1`？**
A：正常拼接成 `https://gw.company.com/llm/v1/models`。我们的规则只追加，不改写路径，
所以任何带前缀的网关都能用。这也是不采用"解析出 host 再重新拼"的原因。

---

## 七、自检清单

- [ ] 能背出至少 4 家厂商的 base_url 形态及其差异
- [ ] 能解释 `v\d+[a-z]*` 为什么能同时匹配 `v1` / `v3` / `v1beta`
- [ ] 知道 DeepSeek 是"不带版本段"的特例
- [ ] 能说出 `normalize` 四个步骤各自防的是什么
- [ ] 理解"能自动判断的事不问用户"这条原则
