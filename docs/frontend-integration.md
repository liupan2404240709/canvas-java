# 前端对接：现在能不能接？还缺什么？

> 写于阶段 3 完成后（2026-11-16）。结论先行：**现在接不上，而且不是改个代理地址的事**。
> 本文说明原因、缺口清单、以及"如果想尽快看到界面"的最小路径。

## 一、结论

| 问题 | 答案 |
| --- | --- |
| 改个 `vite proxy` 就能连上吗？ | **不能**。前端没有任何一处会调 `/api/v1`，代理加上了也没有请求走过去 |
| 为什么？ | 它是纯前端应用：数据在 IndexedDB，模型请求由浏览器直连三方，用户态是空壳 |
| 那现在怎么看到效果？ | 用后端自带的可视化控制台 `http://127.0.0.1:8081/api/demo` |
| 正式对接要多久？ | 属于阶段 9，依赖阶段 4~7 的接口先补齐 |

## 二、证据：前端没有"后端"这一层

| 位置 | 现状 | 说明 |
| --- | --- | --- |
| `web/vite.config.ts` | 没有 `server.proxy` | 不存在任何指向 8081 的转发规则 |
| `web/src/services/api/` | `image.ts` / `video.ts` / `audio.ts` 直连 OpenAI 兼容接口 | 请求打的是用户自己填的 `baseUrl`（`use-config-store`），不是我们的后端 |
| `web/src/services/api/local-proxy.ts` | 对接 `canvas-proxy`（Node 小代理） | 目的只是绕过 CORS，不是业务接口 |
| `web/src/stores/canvas/use-canvas-store.ts` | `zustand/persist` + `localForageStorage` | 画布存在浏览器 IndexedDB |
| `web/src/stores/use-asset-store.ts` | 同上 | 素材也在 IndexedDB |
| `web/src/stores/use-user-store.ts` | `user: null` + `clearSession()` | 没有 `login`，没有 token，没有登录页 |
| `web/src/router.tsx` | 8 条路由，没有 `/login` | 也没有路由守卫 |

也就是说：**浏览器里没有任何一行代码知道 `http://127.0.0.1:8081` 的存在**。后端接口再全，也没有调用方。

## 三、接口与页面的对应关系

| 前端页面 | 依赖的接口 | 后端状态 |
| --- | --- | --- |
| `pages/home`（项目列表） | `/v1/projects` 增删改查 | ✅ 阶段 2 |
| `pages/canvas/:id`（画布） | `/v1/projects/{id}` + `/snapshot` | ✅ 阶段 2 |
| `pages/assets`（素材库） | `/v1/assets` + `/v1/files/upload` | ✅ 阶段 3 |
| 全局登录态 | `/v1/auth/*` | ✅ 阶段 1（但前端没有登录页） |
| `pages/prompts`（提示词） | `/v1/prompt-sources`、`/v1/prompts` | ❌ 阶段 4 |
| `pages/config`（渠道与模型） | `/v1/channels`、`/v1/models` | ❌ 阶段 5 |
| `pages/image` / `pages/video`（生成） | `/v1/generation/*` + SSE | ❌ 阶段 6 |
| 画布对话助手 | `/v1/conversations/*` + SSE | ❌ 阶段 7 |

**一半页面有接口、一半没有。** 这意味着现在做前端对接，会得到一个"素材库能用、提示词和生成用不了"的半成品——不如等接口齐了再一次性改。

## 四、真正缺的三类东西

### 1. 数据通道（前端代码，不是后端）

| 要做的事 | 大致改动 |
| --- | --- |
| 一个统一的 HTTP 客户端 | 新建 `web/src/services/api/backend.ts`：axios 实例、`Authorization` 注入、401 静默刷新、统一 `Result` 解包、错误 toast |
| 登录页 + 路由守卫 | 新建 `pages/login`，补 `useUserStore.login/logout/restore`，`router.tsx` 加拦截 |
| 画布 store 改远程 | `use-canvas-store` 从"persist 到 IndexedDB"改成"启动 GET 详情 → 变更后防抖 PUT 快照"，并处理 `40920` 版本冲突 |
| 素材 store 改远程 | `use-asset-store` 的增删改查换成 HTTP，图片地址直接用返回的 `data.dataUrl` |
| Vite 代理 | `server.proxy: { "/api": "http://127.0.0.1:8081" }` |

其中**画布 store 是最难的一块**：它现在是"本地持久化 + 乐观更新"，改成远程后要处理保存失败、版本冲突、离线草稿三件事，不是简单换函数。

### 2. 后端接口（阶段 4~7）

提示词库、渠道与模型、生成任务（含 SSE）、对话助手。这四块是"这项目为什么需要后端"的核心，也是简历上最有价值的部分，不建议跳过。

### 3. 契约细节（后端已就绪，前端要适配）

| 项 | 说明 |
| --- | --- |
| 响应体 | 统一 `{code, message, data, traceId}`，`code !== 0` 即失败，不能只看 HTTP 状态 |
| ID 类型 | 出参 id 是**字符串**（雪花 ID 精度），前端不要 `Number()` |
| 图片地址 | `/api/media/...` 是相对路径，走代理后可直接给 `<img>` |
| 版本冲突 | 保存快照可能返回 `40920`，要提示"已在其他窗口修改"并重新拉取 |
| 时间格式 | ISO8601 字符串（`2026-11-16T10:27:38`），`dayjs` 可直接解析 |

## 五、如果现在就想看到界面

有两个选项，按成本从低到高：

**选项 A：用后端控制台（推荐，零改动）**

```
http://127.0.0.1:8081/api/demo
```

登录 → 「生成演示数据」→ 就能看到 3 张带节点的画布、4 张图片素材、标签筛选、上传秒传、接口流水。它调的是和正式前端完全相同的接口，能验证后端是否真的可用。

**选项 B：只接素材库（约半天）**

保留其它页面的本地存储，只把 `pages/assets` 切到后端：
1. 加 `backend.ts` 与登录页（素材接口需要 token）；
2. `use-asset-store` 只改列表/新增/删除三个方法；
3. 加 Vite 代理。

好处是能真实验证"上传 → 列表 → 筛选"这条链路。坏处是改动会留下"一半本地一半远程"的中间态，到阶段 9 还要再改一次，所以我倾向于**不做**，除非你想早点看到真实页面。

## 六、阶段 9 的对接计划（草稿）

| 步骤 | 内容 | 验收 |
| --- | --- | --- |
| 9.1 | `backend.ts` + 登录页 + 路由守卫 | 未登录跳 `/login`，登录后 token 自动注入 |
| 9.2 | 首页项目列表接 `/v1/projects` | 新建/重命名/删除/回收站全部走后端 |
| 9.3 | 画布页接详情与快照 | 刷新后内容还在；两个标签页同时改，后保存的收到 40920 |
| 9.4 | 素材页接 `/v1/assets` | 拖拽上传命中秒传，筛选与标签走后端 |
| 9.5 | 提示词页接 `/v1/prompts` | 搜索、刷新源可用 |
| 9.6 | 配置页接 `/v1/channels` | Key 不再存 localStorage，出参不回显明文 |
| 9.7 | 生成页接 `/v1/generation` + SSE | 进度条由服务端推送，不再是前端假进度 |
| 9.8 | 去掉 IndexedDB 依赖 | 换设备登录能看到同一份数据 |

## 七、一个提醒

前端改造会**削弱原项目"纯本地、无需部署"的卖点**。如果这个项目同时用于简历展示，建议：

- 保留一份 `infinite-canvas-main` 原版（纯前端可离线用）；
- 后端对接做成独立分支或新目录，README 里讲清"两个版本的区别与取舍"。

这样面试时可以讲"我知道什么时候该有后端、什么时候不该"，比只讲"我把前端接到了后端"更有说服力。
