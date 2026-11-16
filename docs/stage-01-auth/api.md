# 阶段 1 接口文档：账号体系

> 通用约定（统一响应结构、错误码规则、时间格式、traceId）见 `../stage-00-scaffold/api.md`，本篇只写本阶段新增的接口。

## 一、接口总览

| # | 方法 | 路径 | 认证 | 说明 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/api/v1/auth/register` | 否 | 注册，成功直接返回令牌 |
| 2 | POST | `/api/v1/auth/login` | 否 | 登录，支持用户名或邮箱 |
| 3 | POST | `/api/v1/auth/refresh` | 否（凭 refreshToken） | 刷新令牌，旧 refresh 立即作废 |
| 4 | POST | `/api/v1/auth/logout` | 是 | 登出，拉黑 access + 撤销 refresh |
| 5 | GET | `/api/v1/users/me` | 是 | 当前登录用户信息 |
| 6 | PUT | `/api/v1/users/me` | 是 | 修改昵称 / 头像 |

**认证方式**：`Authorization: Bearer <accessToken>`

## 二、通用约定补充

### 1. 令牌类型说明

| 令牌 | 有效期 | 用途 | 出现位置 |
| --- | --- | --- | --- |
| `accessToken` | 30 分钟（`app.jwt.access-token-ttl`） | 访问业务接口 | 请求头 `Authorization` |
| `refreshToken` | 14 天（`app.jwt.refresh-token-ttl`） | 只用于换新的 accessToken | 请求体 |

两者**不能混用**：拿 refresh 当 access 访问业务接口会返回 `40112`；拿 access 调刷新接口也会返回 `40112`。

### 2. 时间字段单位

- `accessExpiresIn` / `refreshExpiresIn` 是**秒数**（`1800` / `1209600`），不是时间戳。
- 时间戳转成时间看 `data.user.createdAt`，格式 `yyyy-MM-dd'T'HH:mm:ss`。

### 3. null 字段会被省略

全局配置 `default-property-inclusion: non_null`，所以字段为 null 时**响应里没有这个 key**，不是 `"key": null`。

实测：注册时不填邮箱，返回的用户对象里**没有 `email` 字段**：

```json
"user": {
  "id": "2121172897016397826",
  "username": "api_demo",
  "nickname": "api_demo",
  "createdAt": "2026-11-13T17:26:42"
}
```

前端取值请统一用 `data.email ?? ''`。

### 4. id 是字符串

`user.id` 是 19 位雪花 ID，序列化时用 `ToStringSerializer` 转成字符串。**前端不要 `parseInt`**，会丢精度。

### 5. 在 Swagger 里调受保护接口

1. 先调 `POST /v1/auth/login` 拿到 `accessToken`
2. 右上角 **Authorize**，输入框里**只粘 token 本身**，不要带 `Bearer ` 前缀
3. 成功后受保护接口（`/users/me`、`/auth/logout`）会显示小锁图标

判断 token 是否真的发出去了：点 Execute 后看响应区的 `Curl`，必须有 `-H 'Authorization: Bearer eyJ...'` 这一行。

| 现象 | 原因 |
| --- | --- |
| Authorize 显示已授权，但接口仍 `40100` | 请求没带 `Authorization` 头（文档没挂 `SecurityRequirement`），不是 token 问题 |
| 返回 `40112 登录凭证无效` | 输入框里多写了 `Bearer `，实际发出去的是 `Bearer Bearer eyJ...` |

## 三、接口详解

### 1. 注册

```
POST /api/v1/auth/register
Content-Type: application/json
```

**请求体**

| 字段 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `username` | string | 是 | 4-20 位，字母/数字/下划线 | 登录名，全局唯一 |
| `password` | string | 是 | 8-64 位 | 明文传输（生产必须走 HTTPS） |
| `nickname` | string | 否 | ≤ 32 字符 | 不传则默认等于 username |
| `email` | string | 否 | 邮箱格式，≤ 128 字符 | 全局唯一，存储时统一转小写 |

```json
{
  "username": "canvas_demo",
  "password": "canvas1234",
  "nickname": "画布演示",
  "email": "Demo@Example.com"
}
```

**成功响应（200）**：真实输出，token 已截断

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOi...（省略）",
    "tokenType": "Bearer",
    "accessExpiresIn": 1800,
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOi...（省略）",
    "refreshExpiresIn": 1209600,
    "user": {
      "id": "2121172897016397826",
      "username": "api_demo",
      "nickname": "api_demo",
      "createdAt": "2026-11-13T17:26:42"
    }
  },
  "traceId": "c96cf9e2e5fc4d75a2878b69baeb4efb"
}
```

> 上面这条是**不传 nickname / email** 的注册结果：昵称自动填成用户名，`email` 字段整体消失。

**失败响应**

| 场景 | HTTP | code | message |
| --- | --- | --- | --- |
| 用户名格式不对 | 400 | 40000 | `username: 用户名需为 4-20 位字母、数字或下划线` |
| 密码太短 | 400 | 40000 | `password: 密码长度需为 8-64 位` |
| 邮箱格式不对 | 400 | 40000 | `email: 邮箱格式不正确` |
| 用户名已存在 | 409 | 40910 | `用户名已被占用` |
| 邮箱已存在 | 409 | 40911 | `邮箱已被占用` |

> 参数校验失败时，多个错误会拼在一起，用 `; ` 分隔，顺序按字段名排序。

### 2. 登录

```
POST /api/v1/auth/login
Content-Type: application/json
```

**请求体**

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `account` | string | 是 | **用户名或邮箱**都可以，≤ 128 字符 |
| `password` | string | 是 | 明文 |

```json
{ "account": "canvas_demo", "password": "canvas1234" }
```

邮箱登录不区分大小写（实测 `DEMO@example.com` 可以登录 `demo@example.com` 注册的账号）。

**成功响应（200）**：结构与注册完全相同。

**失败响应**

| 场景 | HTTP | code | message |
| --- | --- | --- | --- |
| 账号或密码错（还有次数） | 401 | 40110 | `用户名或密码错误，还可尝试 4 次` |
| 达到上限 | 429 | 42910 | `登录失败次数过多，请 15 分钟后再试` |
| 锁定期内（哪怕密码正确） | 429 | 42910 | 同上 |
| 账号被禁用 | 401 | 40111 | `账号已被禁用` |

**锁定规则**

- 同一账号连续失败 **5 次** 后锁定 **15 分钟**（配置项 `app.login-protection.max-attempts` / `lock-duration`）；
- 登录成功后计数清零；
- 账号不存在也会计数（防止通过"是否计数"判断账号是否存在）；
- 解锁：等 TTL 到期，或手动 `docker exec canvas-redis redis-cli DEL auth:login:fail:<账号小写>`。

### 3. 刷新令牌

```
POST /api/v1/auth/refresh
Content-Type: application/json
```

**请求体**

```json
{ "refreshToken": "eyJhbGciOiJIUzI1NiJ9..." }
```

**成功响应（200）**：返回**一对全新的令牌**，结构和注册一样。

**关键行为：轮换**。旧 `refreshToken` 在返回前就被删除，同一个 refreshToken 只能用一次。前端必须用响应里的新值替换本地存储。

**失败响应**

| 场景 | HTTP | code | message |
| --- | --- | --- | --- |
| 签名错 / 格式错 / 不是 refresh 类型 | 401 | 40112 | `登录凭证无效，请重新登录` |
| 已过期 | 401 | 40112 | `登录凭证无效，请重新登录` |
| 已被使用过 / 已登出 / 已轮换 | 401 | 40114 | `登录凭证已失效，请重新登录` |
| 用户被禁用 | 401 | 40111 | `账号已被禁用` |

> 注意：refresh 过期返回的是 `40112` 而不是 `40113`。因为 `40112/40114` 都要求"清态跳登录"，前端不需要区分。

### 4. 登出

```
POST /api/v1/auth/logout
Authorization: Bearer <accessToken>
Content-Type: application/json

{ "refreshToken": "..." }        ← 可选
```

- `accessToken` 必带（否则 401），它会被加入黑名单直至自然过期；
- `refreshToken` 可选：带了就一并撤销（只能撤销自己的，否则忽略）；
- **幂等**：refreshToken 已过期/格式错不会报错，仍返回成功。

**成功响应（200）**

```json
{ "code": 0, "message": "success", "traceId": "85a09b564e87410e9ce7e9b06d74f88e" }
```

**登出后立即验证**（实测）：

| 用什么东西 | 结果 |
| --- | --- |
| 刚才的 accessToken | `401` / `40114` |
| 刚才的 refreshToken | `401` / `40114` |

### 5. 当前用户

```
GET /api/v1/users/me
Authorization: Bearer <accessToken>
```

**成功响应（200）**

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "id": "2121172325072723970",
    "username": "canvas_demo",
    "nickname": "画布演示",
    "email": "demo@example.com",
    "createdAt": "2026-11-13T17:24:26"
  },
  "traceId": "e304f6e2d16e46b686f4c0b5d04e8f5a"
}
```

**失败响应**

| 场景 | HTTP | code |
| --- | --- | --- |
| 没带 token | 401 | 40100 |
| token 格式错 / 签名错 | 401 | 40112 |
| token 过期 | 401 | 40113 |
| 已登出 / 已撤销 | 401 | 40114 |
| token 有效但用户已被删 | 401 | 40114 |

### 6. 修改个人资料

```
PUT /api/v1/users/me
Authorization: Bearer <accessToken>
Content-Type: application/json
```

**请求体**（字段都可选，按"部分更新"语义）

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `nickname` | string | ≤ 32 字符。**不传 = 不改** |
| `avatarUrl` | string | ≤ 512 字符，须 `http(s)://` 开头或 `/` 开头；**传空串 = 清空** |

```json
{ "nickname": "画布演示" }
```

**成功响应（200）**：返回更新后的完整用户对象（结构与 `/me` 相同）。

**语义对照**（实测过三种情况）

| 请求体 | 结果 |
| --- | --- |
| `{"nickname":"新昵称"}` | 昵称变了，`avatarUrl` 保持原值（响应里没这个 key 说明它本来就是 null） |
| `{"avatarUrl":""}` | 头像被清空（响应里 `avatarUrl` 消失） |
| `{"avatarUrl":"javascript:alert(1)"}` | `400` / `avatarUrl: 头像需为 http(s) 地址或站内路径` |
| `{}` | 不执行任何 UPDATE，直接返回当前信息 |

## 四、前端接入建议

### 1. 请求封装

```ts
// 伪代码
async function request(path, options = {}) {
  const res = await fetch(`/api/v1${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...options.headers,
    },
  });
  const body = await res.json();
  if (body.code === 40113) {
    // access 过期：刷新一次再重试原请求
    await refreshOnce();
    return request(path, options);
  }
  if ([40112, 40114].includes(body.code)) {
    clearAuth();
    router.push('/login');
  }
  if (body.code !== 0) throw new ApiError(body.code, body.message);
  return body.data;
}
```

### 2. 刷新要加"单飞"锁

```ts
let refreshing: Promise<void> | null = null;

function refreshOnce() {
  if (!refreshing) {
    refreshing = doRefresh().finally(() => { refreshing = null; });
  }
  return refreshing;
}
```

不加锁的后果：页面同时发 5 个请求，全部收到 40113，触发 5 次刷新。第一次刷新成功后 refresh token 轮换了，剩下 4 次拿旧 token 去刷，全部 `40114`，用户被踢下线。**这是接入 refresh token 最常踩的坑。**

### 3. 提前刷新比"出错再刷新"更稳

拿 `accessExpiresIn`（1800 秒）自己倒计时，提前 60 秒刷新，能避免请求先失败一次再重试的额外延迟。

### 4. 登出要做两件事

```ts
async function logout() {
  const refreshToken = localStorage.getItem('refreshToken');
  try {
    await request('/auth/logout', { method: 'POST', body: JSON.stringify({ refreshToken }) });
  } finally {
    clearAuth();   // 无论接口成败都清本地
  }
}
```

服务端登出是"尽力而为"：网络断了接口失败，本地也必须清掉，否则用户会卡在一个"看起来登录着但所有请求都 401"的状态。

## 五、后续接口规划

| 接口 | 阶段 | 说明 |
| --- | --- | --- |
| `PUT /v1/users/me/password` | 2 | 改密码，需验证旧密码，改成功后撤销所有 refresh |
| `POST /v1/auth/forgot-password` | 后续 | 发验证码 |
| `POST /v1/auth/reset-password` | 后续 | 凭验证码重置 |
| `GET /v1/users/me/sessions` | 后续 | 查看所有登录设备（依赖 `auth:refresh:*` 扫描） |
| `DELETE /v1/users/me/sessions/{id}` | 后续 | 踢掉指定设备 |
| `GET /v1/canvases` | 2 | 画布列表（依赖本阶段的当前用户） |
