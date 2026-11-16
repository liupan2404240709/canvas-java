# 阶段 1：账号体系

> 目标：从"谁都能调接口"升级到"必须登录才能调接口"。交付一套可用的注册 / 登录 / 刷新 / 登出 / 个人资料能力，并且把 Spring Security、JWT、BCrypt、Redis 撤销机制这些面试高频点真正跑通。

**状态：✅ 已完成，并通过本机全链路实测（见第六节）**

## 一、本阶段完成了什么

| 能力 | 接口 | 说明 |
| --- | --- | --- |
| 注册 | `POST /api/v1/auth/register` | 用户名/邮箱唯一、BCrypt 存密码、注册即登录 |
| 登录 | `POST /api/v1/auth/login` | 支持用户名或邮箱、失败计数与锁定 |
| 刷新 | `POST /api/v1/auth/refresh` | refresh token 轮换，旧的立刻作废 |
| 登出 | `POST /api/v1/auth/logout` | access 拉黑 + refresh 撤销 |
| 当前用户 | `GET /api/v1/users/me` | 前端刷新页面后恢复登录态 |
| 改资料 | `PUT /api/v1/users/me` | 只更新传了的字段 |

配套能力：`t_user` 表自动建、12 个业务错误码、401/403 统一 JSON 响应、登录失败锁定、密码时序防护。

## 二、交付清单

### 新增源码（22 个文件）

```
src/main/java/com/infinitecanvas/canvas/
├── config/
│   └── SecurityConfig.java                    过滤链、放行清单、PasswordEncoder
├── security/                                   ← 本阶段的核心，独立成包
│   ├── JwtProperties.java                     app.jwt 配置绑定
│   ├── LoginProtectionProperties.java         app.login-protection 配置绑定
│   ├── JwtService.java                        签发 / 解析，唯一碰 jjwt 的地方
│   ├── IssuedToken.java                       签发结果（token + jti + 过期时间）
│   ├── LoginUser.java                         SecurityContext 里的主体
│   ├── JwtAuthenticationFilter.java           字符串 → 当前用户
│   ├── TokenStore.java                        Redis 白名单 / 黑名单
│   ├── LoginAttemptGuard.java                 失败计数与锁定
│   ├── SecurityUtils.java                     取当前用户的唯一入口
│   ├── RestAuthenticationEntryPoint.java      401 统一 JSON
│   └── RestAccessDeniedHandler.java           403 统一 JSON
├── entity/User.java                           与 t_user 对应
├── mapper/UserMapper.java                     BaseMapper，零 SQL
├── dto/
│   ├── RegisterRequest.java
│   ├── LoginRequest.java
│   ├── RefreshTokenRequest.java
│   └── UpdateProfileRequest.java
├── vo/
│   ├── UserVO.java                            id 转字符串
│   └── TokenVO.java                           令牌对 + 用户
├── service/
│   ├── AuthService.java
│   ├── UserService.java
│   └── impl/
│       ├── AuthServiceImpl.java               注册登录刷新登出
│       └── UserServiceImpl.java               资料读写
└── controller/
    ├── AuthController.java
    └── UserController.java
```

### 新增资源与文档

```
src/main/resources/db/schema.sql               t_user 建表脚本（幂等）
docs/stage-01-auth/README.md                   本文档
docs/stage-01-auth/design.md                   设计文档
docs/stage-01-auth/api.md                      接口文档
docs/stage-01-auth/tech/                       12 篇逐模块技术说明 + 索引
```

### 修改的已有文件

| 文件 | 改动 |
| --- | --- |
| `pom.xml` | 加 `spring-boot-starter-security`、jjwt 三件套（api / impl / jackson） |
| `CanvasApplication.java` | 加 `@MapperScan`；排除 `UserDetailsServiceAutoConfiguration` |
| `application.yml` | 加 `app.jwt`、`app.login-protection` |
| `application-dev.yml` | 加 `spring.sql.init`，启动时执行 `schema.sql` |
| `ResultCode.java` | 新增 10 个业务码（401xx / 409xx / 429xx） |
| `ApiPath.java` | 新增 `AUTH`、`USERS` 常量 |
| `OpenApiConfig.java` | 补全局 `SecurityRequirement`，让 Swagger UI 真的把 token 塞进请求头 |

## 三、启动步骤

前置：MySQL 与 Redis 容器已经在跑（`docker compose ps` 两个 healthy）。

```powershell
cd D:\project\canvas-java
mvn spring-boot:run
```

不需要手动建表——`schema.sql` 会在启动时执行，全部是 `CREATE TABLE IF NOT EXISTS`。

启动成功的标志：

```
Tomcat started on port 8081 (http) with context path '/api'
Started CanvasApplication in 4.989 seconds
```

> 如果看到 `Using generated security password: xxxx`，说明 `UserDetailsServiceAutoConfiguration` 没排除掉，检查启动类的 `exclude`。

## 四、验收：手工跑一遍

下面的命令可以直接粘进 cmd / PowerShell（PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，用 `curl.exe` 更稳）。

### 1. 注册

```cmd
curl -s -X POST http://127.0.0.1:8081/api/v1/auth/register ^
  -H "Content-Type: application/json" ^
  -d "{\"username\":\"canvas_demo\",\"password\":\"canvas1234\",\"nickname\":\"画布演示\",\"email\":\"demo@example.com\"}"
```

### 2. 用返回的 accessToken 查自己

```cmd
curl -s http://127.0.0.1:8081/api/v1/users/me -H "Authorization: Bearer 粘贴accessToken"
```

### 3. 不带 token 访问（应 401）

```cmd
curl -s http://127.0.0.1:8081/api/v1/users/me
```

期望：`{"code":40100,"message":"未登录或登录已过期",...}`

### 4. 重复注册（应 409）

期望：`{"code":40910,"message":"用户名已被占用",...}`

### 5. 密码错 5 次（看剩余次数到锁定）

期望：第 1~4 次 `40110 用户名或密码错误，还可尝试 N 次`，第 5 次 `42910 登录失败次数过多，请 15 分钟后再试`。

**解锁方式**（不想等 15 分钟）：

```cmd
docker exec canvas-redis redis-cli DEL auth:login:fail:canvas_demo
```

### 6. 用 Swagger 调受保护接口

`http://127.0.0.1:8081/api/swagger-ui.html` → 右上角 **Authorize** → 输入框里**只粘 accessToken 本身**。

- ✅ 正确：`eyJhbGciOiJIUzI1NiJ9.eyJqdGkiOi...`
- ❌ 错误：`Bearer eyJhbGciOiJIUzI1NiJ9...` —— 选的是 `http/bearer` 方案，Swagger 会自己拼 `Bearer `，再手写一次就变成 `Bearer Bearer eyJ...`，后端解出来是 `40112`。

点 Authorize 后，`/v1/users/me`、`/v1/auth/logout` 右上角会出现**小锁图标**，说明 token 会被自动附加；`/v1/auth/login`、`/v1/auth/register`、`/v1/auth/refresh`、`/v1/health` **没有**小锁，因为它们被显式标成了公开接口。

判断有没有真的带上：点 Execute 后看 `Curl` 那一行，必须包含 `-H 'Authorization: Bearer eyJ...'`。没有这一行就是没带上，跟 token 对不对无关。

## 五、判断验收通过的标准

| 检查项 | 期望 |
| --- | --- |
| 表自动创建 | `docker exec canvas-mysql mysql -uroot -proot -e "USE infinite_canvas; DESC t_user;"` 有 12 列 |
| 注册后能立刻访问 `/me` | `code=0`，返回 `id` 是**字符串** |
| 密码入库形式 | `password_hash` 以 `$2a$10$` 开头 |
| 无 token 访问 | 401 + `code=40100` |
| 过期 token | 401 + `code=40113` |
| 登出后原 token | 401 + `code=40114` |
| CORS 预检 | `OPTIONS` 返回 200 且带 `Access-Control-Allow-Origin` |
| Swagger | `http://127.0.0.1:8081/api/swagger-ui.html` 能看到"认证""用户"两组接口；Authorize 后受保护接口的 `Curl` 里有 `Authorization` 头 |

## 六、实测记录（真实输出）

以下是本机实际跑出来的结果，不是设计推演。

### 启动与建表

```
Tomcat started on port 8081 (http) with context path '/api'
Started CanvasApplication in 4.989 seconds

GET /api/v1/health
{"code":0,"data":{"status":"UP","db":{"status":"UP","latencyMs":29},
"redis":{"status":"UP","latencyMs":787},"checkedAt":"2026-11-13T17:23:59"}}
```

`db` / `redis` 都是 UP，说明 `schema.sql` 执行成功、Redis 连上了。

### 完整业务链路

| # | 操作 | 实际结果 |
| --- | --- | --- |
| 1 | 注册 `canvas_demo` | `code=0`，`userId=2121172325072723970`（字符串输出），`createdAt=2026-11-13T17:24:26`，邮箱被归一化成 `demo@example.com` |
| 2 | 重复注册 | `409` / `40910 用户名已被占用` |
| 3 | 用注册返回的 accessToken 查 `/me` | `200` / `code=0` |
| 4 | 用 `DEMO@example.com` 登录 | `200`，邮箱大小写混用也能登录（排序规则 `ai_ci` 生效） |
| 5 | 密码错 4 次 | 依次返回"还可尝试 4 / 3 / 2 / 1 次" |
| 6 | 第 5 次错 | `429` / `42910 登录失败次数过多，请 15 分钟后再试` |
| 7 | 锁定期间用正确密码 | 仍然 `429`，锁定优先于密码校验 |
| 8 | 清 Redis 计数后登录 | `200`，正常签发 |
| 9 | 用 refresh 换新 | `200`，新旧 refresh token 不同（轮换生效） |
| 10 | 用**旧** refresh 再换一次 | `401` / `40114 登录凭证已失效` |
| 11 | 用 access 调刷新接口 | `401` / `40112 登录凭证无效` |
| 12 | 改昵称（不传头像） | `200`，昵称变了，`avatarUrl` 未被清空 |
| 13 | 传空串清空头像 | `200`，`avatarUrl` 从响应中消失（null 字段被全局 `non_null` 省略） |
| 14 | 传 `javascript:alert(1)` 当头像 | `400` / `avatarUrl: 头像需为 http(s) 地址或站内路径` |
| 15 | 登出 | `200` |
| 16 | 登出后用同一个 access token | `401` / `40114`（黑名单生效） |
| 17 | 登出后用 refresh token | `401` / `40114`（白名单已删） |
| 18 | 未登录访问 `/me` | `401` / `40100` |

安全边界（用 shell 手工构造的 token 验证）：

| 场景 | 结果 |
| --- | --- |
| 无 token | `40100` |
| 乱写的 `abc.def.ghi` | `40112` |
| 本地用同一密钥签的**过期** token | `40113`（说明过期和无效能区分开） |
| 用 refresh 类型的 token 访问业务接口 | `40112` |
| 合法 token | 放行，进入业务逻辑 |
| CORS 预检（Origin: localhost:5173） | `200`，带 `Access-Control-Allow-Origin` / `Allow-Credentials` |

### 实测发现并修掉的问题

**问题：token 头是 HS384，不是预期的 HS256。**

第一次跑通后解出 token 头是 `{"alg":"HS384"}`。原因是 jjwt 的 `signWith(key)` 会**按密钥长度自动选算法**：当前 secret 52 字节，落在 48~63 区间，被选成 HS384。

改法是显式传算法：`.signWith(key, Jwts.SIG.HS256)`。改完重启验证，token 头变成 `{"alg":"HS256"}`。

这个坑的价值在于：**它不会报错**。代码能跑、token 能用，只是算法和文档不一致。如果不解一次 token 头，根本发现不了。已写进 `tech/02` 的常见坑。

**问题：Swagger 点了 Authorize 仍然 40100。**

现象很迷惑：Authorize 弹窗里明明显示 `Authorized`、value 也填了 `******`，但 `GET /v1/users/me` 还是 `40100 未登录或登录已过期`。看响应里的 `Curl` 一眼就能定性——那一行只有：

```
curl -X 'GET' 'http://localhost:8081/api/v1/users/me' -H 'accept: */*'
```

**没有 `Authorization` 头**。所以不是 token 错，是根本没发出去。

根因在 `OpenApiConfig`：只写了 `components.addSecuritySchemes(...)`，这相当于"声明这个 API 有 bearer 认证方式"，Swagger UI 因此会显示 Authorize 按钮；但文档里没有任何 `security` 字段引用它，UI 就不知道哪个接口要用、也就不会往请求里塞头。**声明和使用是两件事**，缺一不可。

修法是补一个全局 `SecurityRequirement`：

```java
.addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
```

再加一层：全局要求认证会让 `login` / `register` / `refresh` / `health` 也显示小锁，语义不对。这几个方法上加空的 `@SecurityRequirements` 覆盖掉，生成文档里就是 `security: []`（显式表示"不需要认证"，区别于"继承全局"）。

改完实测生成的 `/v3/api-docs`：

```
root security: [{'bearerAuth': []}]
/v1/users/me       GET    security=(继承全局)
/v1/auth/login     POST   security=[]
/v1/auth/register  POST   security=[]
/v1/auth/refresh   POST   security=[]
/v1/auth/logout    POST   security=(继承全局)
/v1/health         GET    security=[]
```

这个坑的通用教训：**"界面上显示已授权"不等于"请求带上了凭证"**。排查鉴权问题时先看实际发出的请求头，别信 UI 状态。

### 另外发现的两个真实约束

1. **全局 `non_null` 会省略 null 字段**：`avatarUrl` 为 null 时，响应里根本没有这个 key（不是 `null`）。前端取值要写成 `data.avatarUrl ?? ''`，不能假设字段一定存在。
2. **邮箱唯一索引区分大小写吗？不区分**：`utf8mb4_0900_ai_ci` 是大小写不敏感，所以 `Demo@Example.com` 和 `demo@example.com` 会撞唯一索引。业务层做了 `toLowerCase` 归一化，保证"检查"和"入库"的判断标准一致。

### Redis 里的实际数据

跑到一半时 `redis-cli --scan --pattern "auth:*"` 的输出：

```
auth:refresh:93813512de63494d98d5d33886c8698e
auth:refresh:6f60e7059c084b26af2d3f4d256b5cd4
auth:refresh:5e724df77c09469f850e7adc54234e72
auth:blacklist:96213b9b4c0448d598316d35d989c06f
```

TTL 实测：

| key | TTL | 说明 |
| --- | --- | --- |
| `auth:refresh:*` | `1209388` | 略小于 1209600（14 天），因为已经过了一小会儿 |
| `auth:blacklist:*` | `1599` | **小于 1800**，说明写的是"剩余寿命"而不是固定 30 分钟 |

这两条 TTL 直接验证了 `tech/06` 里讲的"黑名单 TTL 要跟 token 剩余寿命对齐"。

### 中文存储验证

MySQL 命令行里 `SELECT nickname` 显示 `????`，一度以为乱码。用 `HEX(nickname)` 确认是 `E794BBE5B883E6BC94E7A4BA`，正是"画布演示"的 UTF-8 编码，`CHAR_LENGTH` 为 4。

**结论**：数据是对的，乱码只是 Windows 下 mysql 客户端的控制台代码页问题。接口返回的中文也正常。

## 七、本阶段要掌握的知识点

按重要性排序，每条都能在 `tech/` 里找到对应展开：

1. **Spring Security 过滤链**：`SecurityFilterChain` 组成、过滤器顺序、`STATELESS` 的实际含义、放行路径为什么不带 context-path。
2. **JWT**：三段结构、签名覆盖范围、payload 为什么是明文、`jti` / `exp` / `iss` / `sub` 的作用、HS256 与 RS256 的取舍。
3. **JWT 无法撤销的补偿**：refresh 白名单 + access 黑名单，以及为什么两者的选择相反。
4. **BCrypt**：为什么慢是优点、盐在哪里、72 字节限制、`cost` 的调整策略。
5. **时序攻击与用户枚举**：`dummyHash` 的作用、失败文案为什么要统一。
6. **认证 vs 授权**：401 和 403 的语义边界、`/me` 这种结构如何消除越权。
7. **MyBatis-Plus**：`@TableId(ASSIGN_ID)` 雪花 ID、逻辑删除、`MetaObjectHandler` 自动填充、`BaseMapper` 零 SQL。
8. **Redis 在认证里的三种用法**：白名单、黑名单（TTL 对齐剩余寿命）、计数器（TTL 当窗口）。
9. **参数校验**：`@Valid` 的位置、`record` 上的注解、DTO 与 Entity 的边界。
10. **建表规范**：`utf8mb4`、`DATETIME` vs `TIMESTAMP`、唯一索引与 NULL、逻辑删除和唯一索引的冲突。

## 八、常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动报 `UnsupportedClassVersionError` | JDK 版本低于 17 | `java -version` 确认，切到 17 |
| 启动报 `WeakKeyException` | `app.jwt.secret` 少于 32 字节 | 换成长一点的密钥 |
| 启动报 `Table 'infinite_canvas.t_user' doesn't exist` | `spring.sql.init.mode` 没设 `always` | 检查 `application-dev.yml` |
| 所有接口都 401 | `PUBLIC_PATHS` 写成了带 `/api` 前缀 | 去掉 `/api`，那是 context-path |
| 登录接口 401 而不是业务错误 | 同上，登录路径没生效 | 同上 |
| 登录一直提示"还可尝试 N 次"但密码是对的 | 账号处于锁定期 | 等 15 分钟，或 `redis-cli DEL auth:login:fail:用户名` |
| 登出后 token 还能用 | Redis 没连上（黑名单写不进去） | 看 `/api/v1/health` 的 `redis.status` |
| 401 响应中文乱码 | 客户端没按 UTF-8 解析 | 服务端已设 charset，检查客户端 |
| Swagger 里看不到认证接口 | 没放行 `/v3/api-docs/**` | 检查 `PUBLIC_PATHS` |
| Swagger 里点了 Authorize 仍返回 40100 | 文档没挂 `SecurityRequirement`，请求里没带 `Authorization` 头 | 看响应区 `Curl` 确认；`OpenApiConfig` 要有 `.addSecurityItem(...)` |
| Swagger 里返回 40112 而不是 40100 | Authorize 输入框里多写了 `Bearer ` | 只粘 token 本身 |
| 刷新时报 40114，但刚登录 | 多实例部署时 Redis 库不一致 | 检查各实例 `spring.data.redis.database` |
| 前端拿到的 userId 精度不对 | 出参没转字符串 | `UserVO.id` 上有 `toEqualSerializer`，确认改的是出参而不是实体 |

## 九、已知取舍与后续

| 项 | 现状 | 后续 |
| --- | --- | --- |
| 角色/权限 | 只有"登录/未登录"，权限固定 `ROLE_USER` | 阶段 2 之后按需加角色表 |
| 踢掉其他设备 | 做不到（没有用户维度的 token 索引） | 需要时在 Redis 建 `auth:user:{id}:refresh` 集合 |
| token 重放检测 | 只打 warn 日志 | 可加"检测到重放就撤销该用户全部 refresh" |
| 忘记密码 | 未实现 | 需要邮箱验证码 + 重置令牌 |
| 密码强度 | 只校验 8-64 位 | 可加黑名单字典、禁止与用户名相同 |
| 登录 IP | 取 `getRemoteAddr()`，代理后是 127.0.0.1 | 阶段 8 从 `X-Forwarded-For` 取 |
| 表结构管理 | `spring.sql.init`（仅 dev） | 阶段 8 接 Flyway |

## 十、下一阶段

阶段 2 是**画布项目 CRUD + 快照保存**，会用到本阶段的 `SecurityUtils.currentUserId()` 做数据归属。同时会补上阶段 1 埋的两个伏笔：

- `@Version` 乐观锁（多人同时编辑画布）
- `@Transactional`（多表写入：项目 + 节点 + 连线）

文档同样产出三份 + `tech/` 逐模块说明。
