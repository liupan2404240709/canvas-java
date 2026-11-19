# 阶段 5：渠道与模型配置

> 目标：让用户能把自己在**国产厂商**申请的 API Key 接进系统，并且能"先测通再保存"。
>
> 阶段 5 是第一个真正对外发起第三方请求的**用户级**功能（阶段 4 的提示词同步是后台任务，
> 出错只影响自己那条源）。因此除了 CRUD，这一阶段的重心全在四件事上：
> **密钥怎么存、地址怎么归一、出站怎么防、失败怎么说人话**。

---

## 一、这一阶段做了什么

一句话概括：**渠道 = 一个 OpenAI 兼容端点 + 一把加密存储的 Key，模型挂在渠道下，对外再用 `channelKey::modelName` 拼出一个跨渠道的模型清单。**

具体交付：

| 能力 | 说明 |
| --- | --- |
| 渠道 CRUD | 新建 / 详情 / 保存 / 启停 / 删除；路径用业务键 `channelKey` |
| Key 安全 | 明文不落库、不进日志、不进异常；AES-256-GCM 加密，另存脱敏提示供列表展示 |
| 连通性探测 | 打一次上游 `GET /models`，把"通不通"和"有哪些模型"一起回答 |
| 先测后存 | 支持未保存的草稿配置直接探测，不必"建了再删" |
| 模型管理 | 随渠道整体保存，按名字差量增删改；可单独启停 |
| 跨渠道模型 | `GET /v1/models?capability=image`，停用的渠道与模型自动排除 |
| 国产厂商预设 | 百炼 / 方舟 / 智谱 / 硅基流动 / DeepSeek / 本地 Ollama，填好地址与模型名 |
| 可视化控制台 | 新增「渠道与模型」页签：预设下拉、探测、保存、测试、启停、删除 |
| e2e 脚本 | `scripts/stage5-e2e.py`，10 组断言，自带 mock 上游，完全不依赖外网 |

---

## 二、新增文件清单

### 2.1 数据库

| 文件 | 变化 |
| --- | --- |
| `src/main/resources/db/schema.sql` | 追加 `t_channel`、`t_channel_model` 两张表（应用启动时 `CREATE TABLE IF NOT EXISTS`） |

### 2.2 Java（26 个文件）

| 分层 | 文件 | 行数 | 职责 |
| --- | --- | --- | --- |
| entity | `Channel.java` | 69 | 渠道实体，密文 + 脱敏提示两列 |
| entity | `ChannelModel.java` | 50 | 模型实体，`capability` + 可选脚本 |
| mapper | `ChannelMapper.java` / `ChannelModelMapper.java` | 9 / 9 | 只用 BaseMapper，无手写 SQL |
| infra/crypto | `SecretCipher.java` | 146 | AES-256-GCM 加解密 + 脱敏 |
| infra/net | `OutboundHosts.java` | 59 | 出站主机安全校验（与阶段 4 共用） |
| infra/channel | `ChannelApiUrls.java` | 93 | Base URL 归一化 + `/models` 拼接 |
| infra/channel | `ChannelProbe.java` | 243 | 出站探测：超时、限长、状态码翻译、JSON 解析 |
| infra/channel | `ModelCapability.java` | 75 | 能力枚举 + 关键字推断 |
| infra/channel | `ChannelProperties.java` | 41 | 超时 / 限长 / 允许内网 |
| infra/channel | `ChannelPresets.java` | 87 | 6 家国产厂商预设 |
| config | `ChannelHttpConfig.java` | 41 | 独立的 `channelRestClient`（超时与提示词同步不同） |
| dto | `ChannelModelDTO` / `CreateChannelRequest` / `UpdateChannelRequest` / `ChannelProbeRequest` | 31/46/51/34 | 入参 |
| vo | `ChannelVO` / `ChannelModelVO` / `ModelOptionVO` / `ModelCandidateVO` / `ChannelPresetVO` / `ChannelProbeVO` | 46/27/30/28/44/32 | 出参 |
| service | `ChannelService` / `ChannelServiceImpl` | 46 / 555 | 业务编排 |
| controller | `ChannelController` / `ModelController` | 110 / 46 | 9 + 1 个接口 |

### 2.3 修改的既有文件

| 文件 | 变化 |
| --- | --- |
| `common/api/ResultCode.java` | 新增 6 个码：40050 / 40051 / 40052 / 40450 / 50010 |
| `common/constant/ApiPath.java` | 新增 `CHANNELS`、`MODELS` |
| `infra/prompt/PromptUrls.java` | `hostAllowed` 改为委托 `OutboundHosts`（安全逻辑不再复制两份） |
| `application.yml` | 新增 `app.crypto`、`app.channel.*` |
| `static/demo/index.html` | 新增「渠道与模型」页签、表单样式、阶段进度 |
| `static/demo/app.js` | 新增约 300 行渠道相关逻辑（1411 → 1948 行） |

### 2.4 脚本与样例

| 文件 | 说明 |
| --- | --- |
| `scripts/stage5-e2e.py` | 10 组约 60 个断言，内置 mock 上游 |

---

## 三、接口一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/channels` | 渠道列表（含各自模型） |
| GET | `/v1/channels/presets` | 国产厂商预设 |
| GET | `/v1/channels/{channelKey}` | 渠道详情 |
| POST | `/v1/channels` | 新建 |
| PUT | `/v1/channels/{channelKey}` | 保存配置（null = 不改） |
| PATCH | `/v1/channels/{channelKey}/enabled?value=` | 启停 |
| DELETE | `/v1/channels/{channelKey}` | 删除 |
| POST | `/v1/channels/probe` | 探测草稿配置（不落库） |
| POST | `/v1/channels/{channelKey}/test` | 测试已存渠道（结果落库） |
| GET | `/v1/models?capability=` | 跨渠道可用模型 |

字段级契约见 [`api.md`](./api.md)。

**一个刻意的取舍**：探测和测试都返回 **HTTP 200 + `ok=false`**，而不是抛 4xx/5xx。
"连不上"是业务答案，不是请求错误；如果返回 500，前端每个调用点都要写 try/catch
才能显示一句"连接失败"。

---

## 四、怎么跑

### 4.1 前置

Docker 里的 MySQL（3307）与 Redis（6380）已经跑起来即可 —— 本阶段新增的只依赖 MySQL，
Redis 是阶段 1/4 遗留依赖。**不需要任何外部网络**，因为预设只是"填好的表单"，
探测只在你点按钮时才发请求。

### 4.2 启动

```powershell
cd D:\project\canvas-java
mvn spring-boot:run
```

控制台：http://127.0.0.1:8081/api/demo#channels

### 4.3 手工试一遍（建议按这个顺序）

1. 打开「渠道与模型」页签 → 右上「从国产厂商预设开始…」选 **本地 Ollama**（示例，不消耗额度）
2. 表单自动填好 Base URL 与协议，粘一个 Key，点 **探测**
3. 探测成功会自动把上游模型名填进「模型」输入框
4. 点 **保存** → 左侧出现渠道卡片，标签显示「通」
5. 右侧出现可用模型，`id` 形如 `ch_ab12cd34ef56::qwen-image-3.0`
6. 点渠道上的 **停用** → 右侧模型立刻消失（验证"停用渠道连带隐藏模型"）

> 没有真实 Key 也能验证大半功能：`scripts/stage5-e2e.py` 会自己起一个 mock 上游。

### 4.4 e2e

```powershell
python scripts\stage5-e2e.py
```

脚本会：
- 在本机随机端口起一个 mock 上游，模拟 5 种上游行为（正常 / Gemini / 空列表 / 401 / 返回 HTML）
- 用固定账号 `channel_e2e` 登录（不存在则注册），开头清空该账号下的渠道
- 跑 10 组断言，最后汇总失败项并以非 0 退出码结束

### 4.5 配置项

```yaml
app:
  crypto:
    # Key 的加密密钥。SHA-256 派生 32 字节 AES key，所以不需要是 base64。
    # 长度 < 16 直接拒绝启动；换掉它 = 所有已存 Key 解不开（返回 50010 让用户重填）
    secret: canvas-dev-channel-secret-change-me-in-production-2026
  channel:
    connect-timeout: 5s        # 比提示词同步短：这是用户点一下在等的请求
    read-timeout: 15s
    max-response-bytes: 1048576   # /models 正常几十 KB，1MB 已极宽松
    max-models: 500               # 防止上游返回几万条撑爆下拉框
    allow-private-host: true      # 本地 Ollama 是正当场景，所以默认允许内网
```

---

## 五、验收清单

### 密钥安全

- [ ] `POST /v1/channels` 的响应里搜不到明文 Key
- [ ] 列表接口只返回 `apiKeyHint`，形如 `sk-a****wxyz`
- [ ] 数据库 `t_channel.api_key_cipher` 是 `v1:` 开头的 base64，不是明文
- [ ] 同一把 Key 连续建两次渠道，两条密文**不相同**（IV 随机）
- [ ] 改 `app.crypto.secret` 后调用测试接口返回 50010，而不是 500 一堆堆栈

### 地址归一化

- [ ] `.../compatible-mode/v1` → 探测打 `.../compatible-mode/v1/models`
- [ ] `.../api/v3`（方舟）→ 打 `.../api/v3/models`
- [ ] `https://api.deepseek.com`（不带版本段）→ 自动补 `/v1/models`
- [ ] 末尾带 `/` 的地址被归一化，不会拼出 `//models`
- [ ] 填 `ftp://` 或 `not-a-url` → 400 + 40050

### 探测与错误翻译

- [ ] 正常地址 → `ok=true`，`models` 有内容，`latencyMs > 0`
- [ ] 401 → `ok=false`，`httpStatus=401`，`message` 提到"鉴权"
- [ ] 返回 HTML（把控制台首页当接口填）→ `ok=false`，`message` 说明"不是 JSON"
- [ ] 空列表 → `ok=true`，`models` 为空（连通但没模型）
- [ ] Gemini 协议的 `models/` 前缀被剥掉
- [ ] 已存渠道的模型在探测结果里 `exists=true`

### 保存语义

- [ ] `PUT` 只传 `name` → Key 与模型都不变
- [ ] `PUT` 传 `apiKey` → `apiKeyHint` 变化，且 `lastTestOk` 被清空
- [ ] `PUT` 传 `models: []` → 模型清空（与"不传"区分开）
- [ ] 模型重复名 / 空名被去重丢弃
- [ ] 保存后 `sortIndex` 等于传入顺序

### 跨渠道模型

- [ ] `GET /v1/models` 的 `id` 全部形如 `channelKey::name`
- [ ] `capability=image` 只返回生图模型
- [ ] `capability=picture` → 400 + 40052
- [ ] 停用渠道 → 其模型从 `/v1/models` 消失
- [ ] 单独停用某个模型 → 只有它消失

### 归属隔离

- [ ] 另一个账号 `GET/PUT/DELETE/test` 我的渠道 → 全部 404 + 40450
- [ ] 另一个账号的 `/v1/models` 不含我的模型

### 控制台

- [ ] 选预设 → 表单自动填充，Key 输入框不被清空
- [ ] 探测成功后模型输入框自动填好
- [ ] 渠道卡片显示 `hint · N 个模型`、地址、失败原因
- [ ] 停用后卡片变灰，标签从「通」消失

---

## 六、实测记录

> 状态：**代码已通过 `mvn -o compile`（BUILD SUCCESS）。端到端实测待跑。**
>
> 本次会话的沙箱安全策略拦截了 `wsl.exe`，Docker Desktop 无法启动，
> MySQL 不可用，因此 `python scripts/stage5-e2e.py` 还没能真正执行。
> 这一节等实测后补真实输出，**不做任何预填**。

已经做过的、不依赖运行环境的验证：

| 验证项 | 方式 | 结果 |
| --- | --- | --- |
| 编译 | `mvn -o compile` | BUILD SUCCESS |
| Base URL 拼接 | `jshell` 直接调 `ChannelApiUrls.modelsUrl`，10 组真实厂商地址 | 全部符合预期 |
| 前端脚本语法 | `node --check app.js` | 通过 |
| e2e 脚本语法 | `python -m py_compile` | 通过 |

`jshell` 那 10 组的实际输出（这是唯一能脱离数据库验证的核心逻辑）：

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

跑法（Docker 处于 running 后）：

```powershell
cd D:\project\canvas-java
mvn spring-boot:run
# 另一个窗口
python scripts\stage5-e2e.py
```

---

## 七、踩过的坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `LimitExceededException` 编译不过："在相应的 try 语句块中不抛出异常类型" | 读取限长写在 `RestClient.exchange(...)` 的 lambda 里，那个函数式接口**不允许抛受检异常**，于是 `catch` 判成不可达 | 异常改成继承 `RuntimeException` |
| 换 Key 之后仍然显示"连接失败" | `updateById` 默认**忽略 null 字段**，`lastTestError = null` 根本不会生成 `SET` | 成功时用 `LambdaUpdateWrapper.set(..., null)` 显式写 NULL |
| 模型脚本删不掉 | 同上：`updateById` 跳过 null，`script = null` 不生效 | 模型更新改走 wrapper 的 `.set()` |
| 停用渠道后，模型仍出现在 `/models` | `t_channel_model` 只冗余了 `user_id`，不带渠道状态 | 先查启用渠道 id，再在内存里过滤 |
| 把控制台首页当成接口地址填进去，探测却显示成功 | 对方返回 200 + HTML | 解析失败时报"不是 JSON，请确认填的是接口地址" |

---

## 八、FAQ

**Q：为什么不直接把 Key 明文存库？反正只有本人能读。**
A：数据库被拖库、备份文件泄露、慢查询日志打印参数，这三种事故里明文 Key 都是直接损失
（别人的额度会被刷）。加密之后攻击者还需要拿到 `app.crypto.secret`。
严格说这仍是"应用层加密"，比 KMS 弱，但成本几乎为零，是明显的净收益。

**Q：为什么不让前端保存后自己记住 Key，后端不存？**
A：那样每次生成都要前端传 Key，等于 Key 常驻浏览器内存 + 每次请求都过网络。
而且 XSS 一把就全拿走。服务端存密文 + 只回 hint 是标准做法。

**Q：模型为什么不做成渠道表里的 JSON 列？**
A：阶段 6 要按能力查"我有哪些生图模型"，那是一次带 `capability` 条件的查询；
JSON 列上做不了（函数索引只能挡"包含某值"，给不出"按能力筛"）。
而且模型能单独启停、单独挂脚本，本来就有独立生命周期。

**Q：为什么预设里没有 OpenAI / Claude？**
A：这个项目是国产化改写，预设只放国产厂商与本地自建。接口本身是 OpenAI 兼容的，
用户照样可以手动把 `https://api.openai.com/v1` 填进来——预设只是脚手架，不是白名单。

**Q：模型名会不会过时？**
A：会，而且一定会。`deepseek-chat` 就在 2026-07 退役了。所以预设只放**能力明确、名字稳定**的
模型；ID 带日期后缀的（如方舟的文本模型）在 `note` 里写明"以控制台为准"，
长期正确的做法是建好渠道后点一次「探测」用上游真实列表覆盖。

**Q：`allow-private-host: true` 不会变成 SSRF 吗？**
A：会留一个"能判断某个内网端口上有没有 OpenAI 兼容 `/models`"的探测面，但**拿不到响应内容**
（错误只给状态码、成功只回模型名列表、不跟随重定向）。要彻底防死只能做域名白名单，
那样本地 Ollama 也就没了。这是一个明示的取舍，配置文件里写了原因。
