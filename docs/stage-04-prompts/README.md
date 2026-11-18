# 阶段 4　提示词库

对应前端页面：`web/src/pages/prompts/index.tsx`。

一句话概括：**把「第三方仓库里的 JSON」变成「可搜索的本地提示词库」**，并且保证重复同步不会把库写烂、多个实例同时同步不会互相踩。

---

## 一、这块解决什么问题

前端原来的提示词库是"纯浏览器"的：每次进页面，从 7 个 GitHub raw 地址把 JSON 拉下来，在内存里合并成一个数组，搜索就是 `Array.filter`。

搬到后端要解决四件浏览器里不存在的事：

| 浏览器里的做法 | 后端必须面对的问题 | 本阶段的答案 |
| --- | --- | --- |
| 每次进页面重新拉 | 几千条提示词每次全量拉，首屏要等好几秒 | 同步到 MySQL，列表走 SQL 分页 |
| 存在 IndexedDB，用户自己看 | 多用户要隔离，别人不能看到、也不能删我的源 | 每张表都带 `user_id`，越权返回 404 |
| `Array.filter` 搜索 | 数据在数据库里，要写 SQL；还要稳定分页 | `LIKE` + `JSON_CONTAINS` + 三列排序 |
| 没有"同步失败"的概念 | 上游挂了、返回非 JSON、返回空数组 | 失败原因落 `last_error`，**旧数据保留** |
| 刷新就是重新拉一遍 | 每 30 分钟把几千行删了重插，代价太高 | 内容摘要 + ETag + 批量 upsert |

---

## 二、交付清单

### 新增表（2 张，详见 [`docs/database.md`](../database.md)）

| 表 | 定位 | 关键约束 |
| --- | --- | --- |
| `t_prompt_source` | 源：地址、启用状态、同步状态（条数/摘要/ETag/失败原因） | `uk_prompt_source_user_key (user_id, source_key)` |
| `t_prompt` | 提示词：正文、标签、封面、推荐参数 | `uk_prompt_source_key (source_id, prompt_key)` |

### 新增源码

```
infra/prompt/
├── PromptProperties.java     超时、响应体上限、标签缓存 TTL、SSRF 开关
├── PromptSyncException.java  "可预期失败"：落到 last_error，不变成 HTTP 错误
├── PromptUrls.java           协议白名单 + 私网地址检查 + 相对 URL 补全
├── BuiltInPromptSources.java 7 个内置源（与前端 presets 一一对应）
├── PromptFetcher.java        RestClient + 条件请求 + 流式限长
├── PromptJsonParser.java     JSON → ParsedPrompt（容错、去重、截断）
├── PromptJson.java           字符串数组 ↔ JSON 列
└── PromptTagCache.java       标签云缓存（版本号失效 + 空结果缓存）

infra/lock/
└── RedisLock.java            SET NX PX + Lua 比对删除

scheduler/
└── PromptRefreshScheduler.java  每 5 分钟扫一次"到期的源"

service/
├── PromptSourceService(Impl)   源 CRUD、内置源播种、删除连带清提示词
└── PromptService(Impl)         列表查询 + 同步（单源/全部/定时）

controller/
├── PromptController.java       GET /v1/prompts
└── PromptSourceController.java 源的 CRUD 与同步

config/
└── PromptHttpConfig.java       专用 RestClient（独立超时）
```

### 接口（7 个）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/prompts` | 列表：关键字 + 标签（可多个，或关系）+ 分栏 + 分页 |
| GET | `/v1/prompt-sources` | 源列表（含同步状态） |
| POST | `/v1/prompt-sources` | 新增源 |
| PATCH | `/v1/prompt-sources/{sourceKey}` | 局部更新（内置源只能改 enabled / 间隔） |
| DELETE | `/v1/prompt-sources/{sourceKey}` | 逻辑删源 + 物理删提示词 |
| POST | `/v1/prompt-sources/{sourceKey}/refresh` | 同步单源（`?force=true` 强制重写） |
| POST | `/v1/prompt-sources/refresh` | 同步全部启用中的源 |

### 其他

- **可视化控制台**新增「提示词库」页签：源卡片（条数 / 上次同步 / 失败原因 / 同步 / 停用 / 删除）、分栏与标签筛选、提示词网格、复制正文。
- **样例数据**：`src/main/resources/static/demo/prompts-sample.json`（8 条）+ 3 张封面图，由本服务自己托管，**断网也能跑通整条链路**。
- **验收脚本**：`scripts/stage4-e2e.py`，12 组约 45 个断言，不依赖外网。

---

## 三、怎么跑

前提：Docker Desktop 已启动，MySQL(3307) / Redis(6380) 可用。

```powershell
cd D:\project\canvas-java
mvn spring-boot:run
```

启动时会自动执行 `schema.sql` 建表（`CREATE TABLE IF NOT EXISTS`，已有表不受影响）。

### 验收：自动化

```powershell
# 另开一个窗口
python scripts\stage4-e2e.py
```

脚本会自己注册/登录 `prompt_e2e` 账号，用本机样例 JSON 建源、同步、验证搜索与分页，最后清理数据。**不需要外网。**

### 验收：手工（控制台）

1. 打开 http://127.0.0.1:8081/api/demo ，用演示账号进入；
2. 切到「提示词库」，首次会看到左侧 7 个内置源（条数 0、未同步）；
3. 点「建本地样例源」→ 自动建源 + 同步 → 右侧出现 8 张卡片；
4. 再点一次「同步全部源」→ 样例源那行提示「内容未变化，跳过写库」；
5. 点分栏「本地样例库」、点标签「人像」、在搜索框输入「霓虹」，结果随之变化；
6. 在「接口流水」页能看到每一次 `/prompts`、`/prompt-sources/.../refresh` 的业务码与耗时。

### 验收：真实上游（需要外网）

内置源指向 `raw.githubusercontent.com`。国内网络下可能超时，这是**预期行为**，不是 bug——所以它们失败时会写进 `last_error`，而不是把服务搞挂。想验证真实同步，可以在「新增源」里填：

```
https://raw.githubusercontent.com/yukkcat/image-prompts/main/dist/sources/awesome-gpt-image.json
```

能拉到就把内置源那 7 个也点一遍。

---

## 四、验收标准（逐项可勾）

| # | 验收点 | 预期 |
| --- | --- | --- |
| 1 | 首次 `GET /prompt-sources` | 返回 7 个内置源，`builtIn=true`，`count=0` |
| 2 | 内置源改名 | `409 / 40941` |
| 3 | 内置源删除 | `409 / 40941` |
| 4 | 内置源停用 | 200；`GET /prompts` 的 `categories` 里不再有它 |
| 5 | 新增源（http 地址） | 200，`id` 形如 `src_xxxxxxxxxxxx` |
| 6 | 同名再建 | `409 / 40940` |
| 7 | `file:///etc/passwd` | `400 / 40040` |
| 8 | 首次同步样例源 | `success=true, skipped=false, count=8` |
| 9 | 第二次同步（同内容） | `success=true, skipped=true, count=8`（**没写库**） |
| 10 | `?force=true` | `skipped=false`（强制重写） |
| 11 | 列表字段 | `category`=源名、`sourceId`=源业务键、`id`=源内 id |
| 12 | 封面相对路径 | 返回绝对地址 `.../api/demo/images/...` |
| 13 | `createdAt` | ISO 格式 `2025-03-11T09:20:00` |
| 14 | 缺 `sourceUrl` 的条目 | `githubUrl` 回落成源主页 |
| 15 | 关键字命中标题/正文/说明 | 分别都能搜到 |
| 16 | 双标签 | 或关系（命中任一） |
| 17 | 标签云 | 不受已选标签影响（勾了「人像」仍能看到「美食」） |
| 18 | 不存在的分栏 | 200 + 空列表（不报错） |
| 19 | 分页 | 3/3/2 三页合并恰好 8 条，无重复 |
| 20 | `page=0&pageSize=1000` | 压成 `1 / 100` |
| 21 | 同步一个 404 源 | **HTTP 200**，`success=false`，`lastError` 含 `HTTP 404` |
| 22 | 同步非数组 JSON | 200，`lastError` 含「JSON 数组」 |
| 23 | 失败后源上状态 | `lastError` 非空，`count` 保持旧值，旧提示词还在 |
| 24 | 同步全部 | 返回 `results/successCount/failureCount/skippedCount` |
| 25 | 越权同步/删除别人的源 | `404 / 40440` |
| 26 | 新用户列表 | `total=0`（看不到别人的提示词） |
| 27 | 删除源 | 源消失，它的提示词 `total=0` |
| 28 | 定时任务 | 日志出现「定时同步完成/失败」，`scan-cron` 默认 5 分钟扫一次 |

---

## 五、配置项（`app.prompt.*`）

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `scan-cron` | `0 */5 * * * *` | **扫描**间隔，不是刷新间隔 |
| `connect-timeout` | `5s` | 建连超时 |
| `read-timeout` | `20s` | 读超时 |
| `max-response-size` | `8MB` | 响应体上限，流式累计，超了立刻中断 |
| `tag-cache-ttl` | `5m` | 标签云缓存时长 |
| `tag-facet-limit` | `100` | 标签云最多返回多少个 |
| `schedule-batch` | `20` | 单次扫描最多处理多少个到期源 |
| `allow-private-host` | `true` | **生产必须改 false**，否则等于开放内网探测 |

每个源自己的刷新间隔是数据：`t_prompt_source.refresh_interval_minutes`（默认 30，0 = 只手动刷）。

---

## 六、实测记录

> 状态：**代码已通过 `mvn -o compile`（BUILD SUCCESS）。端到端实测待跑。**
>
> 本次会话的沙箱安全策略拦截了 `wsl.exe`，Docker Desktop 无法启动，MySQL/Redis 不可用，因此
> `python scripts/stage4-e2e.py` 还没能真正执行。这一节等实测后补真实输出，**不做任何预填**。

跑完把输出贴回来，或者直接说「跑完了」，我会：
1. 把真实输出补进这一节；
2. 逐项核对第四节 28 条；
3. 如果发现契约不一致，按「改代码不改文档」的原则修（阶段 3 就是这么发现 4 个真 bug 的）。

---

## 七、FAQ / 踩坑

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 同步内置源一直失败，`lastError` 是 `请求失败：Connection timed out` | 国内网络访问 `raw.githubusercontent.com` 不通 | 正常现象；用样例源验证逻辑，或用代理后用自定义源 |
| 同步后条数没变，但 `skipped=true` | 上游内容没变（摘要一致） | 想看它重写就加 `?force=true` |
| 内置源返回 0 条，同步报「已保留本地数据」 | 上游临时故障 | 有意为之：宁可失败也不清空用户库 |
| `categories` 里少了某个源 | 该源被停用（`enabled=0`） | 停用不等于删除，数据还在 |
| 修改内置源的地址报 40941 | 内置源只允许停用 | 要自定义地址就新建一个源 |
| 定时任务没跑 | 源 `refresh_interval_minutes=0`，或还没到间隔 | 手动点同步最直接 |
| 换端口后封面 404 | 样例 JSON 里的封面是相对路径，按源 URL 解析 | 换端口不影响：解析基准就是源的请求地址 |
| 同步一个源要十几秒 | 串行同步、单源读超时 20s | 全局同步是串行设计，避免同时打上游 |

---

## 八、文档导航

📖 逐模块技术说明（含设计取舍与面试考点）在 [`tech/`](./tech/README.md)。

建议阅读顺序：

1. [01 表结构设计](./tech/01-表结构设计.md)：先把两张表和"父子关系"搞清楚；
2. [04 幂等同步](./tech/04-幂等同步与内容摘要.md)：这是整块最核心的设计；
3. [06 分布式锁](./tech/06-Redis分布式锁.md)：为什么需要、三个必做细节；
4. [12 完整链路](./tech/12-完整链路.md)：把上面所有零件串成一条时间线。

- 决策记录（为什么这么选、否决了什么）：[`design.md`](./design.md)
- 接口契约（字段级）：[`api.md`](./api.md)
- 表结构权威说明：[`../database.md`](../database.md)
