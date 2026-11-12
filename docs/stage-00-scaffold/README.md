# 阶段 0：环境与骨架

> 目标：让一个空的 Spring Boot 工程能连上 MySQL 和 Redis，把「统一响应 / 全局异常 / 链路追踪 / 日志 / 接口文档 / ORM 配置」这些地基铺好。后面每个业务模块都直接复用，不用再返工。

## 本目录的三组文档

| 文档 | 回答什么问题 | 适合什么时候看 |
| --- | --- | --- |
| 本文件（README） | 交付了什么、怎么启动、怎么验收、遇到报错怎么办 | 第一次跑起来 |
| [design.md](./design.md) | 整体分层、请求链路、错误码分段、决策记录（D-01~D-07） | 想理解架构全貌 |
| [api.md](./api.md) | 接口约定、错误码表、health 接口详情 | 写前端或联调 |
| **[tech/](./tech/README.md)** | **每个类为什么这么写、面试会怎么问** | **准备面试、复习原理** |

`tech/` 是逐模块的技术说明，12 篇覆盖了本阶段全部源码。每篇的结构固定：一句话定位 → 完整代码 → 逐段拆解（为什么这么写 / 不这么写会怎样）→ 设计决策对比表 → 面试考察点（基础/进阶/陷阱，带答题要点）→ 常见坑 → 自检清单。

面试前建议从 [`tech/README.md`](./tech/README.md) 的「面试话术总纲」开始，先能把项目整体讲顺，再补细节。

## 一、本阶段完成了什么

| 能力 | 落地方式 | 后面谁会用 |
| --- | --- | --- |
| 工程骨架 | Maven + Spring Boot 3.3.5 + JDK 17 | 全部 |
| 中间件 | `docker-compose.yml` 一键起 MySQL 8 / Redis 7 | 阶段 2 起 |
| 统一响应 | `Result<T>` + `ResultCode` | 全部接口 |
| 分页响应 | `PageResult<T>` | 阶段 2 画布列表、阶段 3 素材、阶段 4 提示词 |
| 全局异常 | `GlobalExceptionHandler` + `BizException` | 全部接口 |
| 链路追踪 | `TraceIdFilter` + MDC + 响应头 `X-Trace-Id` | 全部请求 |
| 日志 | `logback-spring.xml`，按天 + 50MB 滚动，保留 14 天 | 排查问题 |
| ORM 配置 | MyBatis-Plus 分页 / 乐观锁 / 防全表更新 + 时间自动填充 | 阶段 2 起 |
| 缓存配置 | `RedisTemplate<String, Object>`，JSON 序列化 | 阶段 2 画布快照、阶段 4 提示词 |
| 接口文档 | SpringDoc，Swagger UI | 全部接口 |
| 跨域 | `WebMvcConfig` 放行 Vite 开发端口 | 阶段 9 前端联调 |
| 健康检查 | `GET /api/v1/health` 探测 MySQL + Redis | 部署、联调自检 |

## 二、文件清单（对照着读代码）

```
canvas-java/                      ← 独立工程，与前端仓库 infinite-canvas-main 平级
├── pom.xml
├── docker-compose.yml
├── README.md
├── docs/                         本阶段文档（plan.md + stage-00-scaffold/）
└── src/main
    ├── java/com/infinitecanvas/canvas/
    │   ├── CanvasApplication.java                 启动类
    │   ├── common/
    │   │   ├── api/Result.java                    统一响应体
    │   │   ├── api/ResultCode.java                业务码 + HTTP 状态码
    │   │   ├── api/PageResult.java                分页响应体
    │   │   ├── constant/ApiPath.java              路径常量（/v1）
    │   │   ├── constant/TraceId.java              traceId 常量与读取
    │   │   └── exception/BizException.java        业务异常
    │   │   └── exception/GlobalExceptionHandler.java  全局异常处理
    │   ├── config/
    │   │   ├── MybatisPlusConfig.java             三个拦截器
    │   │   ├── AuditMetaObjectHandler.java        createdAt/updatedAt 自动填充
    │   │   ├── JacksonConfig.java                 LocalDateTime 序列化（秒级 ISO）
    │   │   ├── RedisConfig.java                   键值序列化
    │   │   ├── WebMvcConfig.java                  CORS
    │   │   └── OpenApiConfig.java                 Swagger 元信息
    │   ├── controller/HealthController.java       健康检查接口
    │   ├── service/HealthService.java             探针逻辑
    │   ├── vo/HealthVO.java                       出参
    │   └── infra/web/TraceIdFilter.java           traceId 过滤器
    └── resources/
        ├── application.yml                        公共配置
        ├── application-dev.yml                    本地 MySQL / Redis 连接
        └── logback-spring.xml                     日志格式与滚动
```

## 三、环境要求

- JDK 17（`java -version` 确认）
- Maven 3.9+（或用 IDEA 自带）
- Docker Desktop（MySQL 8 + Redis 7 都跑在容器里）

> 端口说明（三处都改过，别按网上教程的默认端口照抄）：
>
> | 服务 | 容器内端口 | 宿主机端口 | 为什么 |
> | --- | --- | --- | --- |
> | canvas-server | 8081 | 8081 | 8080 常被其他 Java 项目占用 |
> | MySQL | 3306 | **3307** | 本机可能已装 MySQL 并占用 3306 |
> | Redis | 6379 | **6380** | 本机可能已装 Memurai/Redis 并占用 6379 |

## 四、启动步骤

### 1. 启动 Docker Desktop

必须先手动启动（开始菜单里点 Docker Desktop，等托盘图标变成 running）。Docker Desktop 未启动时，任何 `docker` 命令都会报 `Cannot connect to the Docker daemon`。

### 2. 起中间件

```powershell
cd D:\project\canvas-java
docker compose up -d
docker compose ps
```

> PowerShell 和 cmd 都执行这一条，**`cd` 后面不要加 `/d`**（见第六节"坑一"）。

`docker compose ps` 要看到两个容器 `healthy`：

```
NAME           STATUS
canvas-mysql   Up (healthy)
canvas-redis   Up (healthy)
```

首次启动 MySQL 会初始化数据目录，实测约 25 秒；`healthy` 之前不要急着起应用。库 `infinite_canvas` 由 `MYSQL_DATABASE` 环境变量自动创建，账号是 `root / root`，与 `application-dev.yml` 已经对上，**不需要手动建库建账号**。

> 首次 `docker compose up -d` 要拉取镜像，国内网络较慢，实测 MySQL 耗时 15 分 40 秒。排查方法和加速器选择见第六节。

### 3. 启动应用

```powershell
mvn spring-boot:run
```

### 启动成功的标志

控制台出现 `Tomcat started on port 8081 (http) with context path '/api'`。

> 第一次 `mvn spring-boot:run` 会下载依赖，耗时几分钟属正常。

### 备选：不用 Docker，复用本机已装的 MySQL / Redis

如果你的 3307 / 6380 也被占用，或者不想用容器，可以改回本机服务：

1. 把 `application-dev.yml` 里的 `3307` 改成 `3306`、`6380` 改成 `6379`；
2. 用你的 MySQL 客户端执行：`CREATE DATABASE infinite_canvas DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`
3. 把 `username` / `password` 改成本机 MySQL 的账号密码。

> 用容器时用 `root/root` 没有问题——它只是本地容器，不对外暴露；如果哪天连到公网或共享环境，再换专用账号。

## 五、验收

```bash
curl -s http://127.0.0.1:8081/api/v1/health
```

期望输出（耗时数字会变）：

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "app": "canvas-server",
    "version": "0.0.1",
    "status": "UP",
    "db": { "status": "UP", "latencyMs": 1 },
    "redis": { "status": "UP", "latencyMs": 1 },
    "checkedAt": "2026-11-11T22:00:23"
  },
  "traceId": "cec6d4180e3f4878a247765580f4febc"
}
```

> 上面是本机实测的真实输出（MySQL 8.0.46 容器 + Redis 7 容器）。

逐项对一下：

1. `code` 是 0，说明统一响应体通了。
2. `db.status` 是 UP，说明 MyBatis-Plus 的数据源、MySQL 连接串、账号密码都对。
3. `redis.status` 是 UP，说明 Redis 连接池和 Lettuce 配置对。
4. `traceId` 有值，说明过滤器生效；再对比响应头 `X-Trace-Id` 和日志行里的中括号内容，三者应该一致。

再验两个异常场景：

```bash
# 404：路径不存在时返回统一结构而不是 Tomcat 默认页
curl -s http://127.0.0.1:8081/api/v1/not-exist

# traceId 透传：传什么就回什么
curl -s -H "X-Trace-Id: my-test-001" http://127.0.0.1:8081/api/v1/health
```

Swagger UI：<http://127.0.0.1:8081/api/swagger-ui.html>

## 六、实测记录（2026-11-11，本机）

这一节是实际跑通的完整记录，包含踩到的坑。照着做可以复现。

### 1. 启动耗时

| 环节 | 耗时 | 说明 |
| --- | --- | --- |
| 拉取 `redis:7-alpine` | 约 5 分钟 | 39.6MB，加速器限速 |
| 拉取 `mysql:8.0` | **15 分 40 秒** | 约 600MB，中途多次 `Retrying in N seconds` |
| MySQL 容器首次初始化 | 约 25 秒 | `health: starting` → `healthy` |
| 应用启动 | **4.575 秒** | 依赖已在本地仓库 |

> 镜像拉取只发生在第一次。之后 `docker compose up -d` 是秒级。

### 2. 镜像拉取卡住的排查方法

如果 `docker compose up -d` 长时间无输出，**不要干等**，按顺序查：

```bash
# ① 看镜像到底有没有下来
docker images | grep -E "mysql|redis"

# ② 单独拉一个，看真实进度（compose 会吞掉进度条）
docker pull mysql:8.0

# ③ 测加速器哪个还活着（200/401 都算活，000 是死了）
for m in docker.1panel.live docker.m.daocloud.io docker.1ms.run dockerproxy.com; do
  echo "$m -> $(curl -s -o /dev/null -w '%{http_code}' --max-time 8 https://$m/v2/)"
done
```

实测结果：`docker.1panel.live` 返回 200、`docker.m.daocloud.io` 和 `docker.1ms.run` 返回 401（正常，需 token），`dockerproxy.com` 和 `docke.nju.edu.cn` 已失效返回 000。

**关键点**：出现 `Retrying in N seconds` 不代表失败，是某一层下载中断后自动重试。只要层 ID 相同，多次重试会复用已下载部分，**不要中途 Ctrl+C**，否则进度会丢。

### 3. 两个必踩的坑

**坑一：PowerShell 里不能用 `cd /d`**

`cd /d D:\path` 是 **cmd** 的语法（`/d` 表示允许跨盘符切换）。PowerShell 的 `cd` 是 `Set-Location` 的别名，会把 `/d` 和路径当成两个位置参数，直接报：

```
Set-Location : 找不到接受实际参数"D:\project\..."的位置形式参数。
```

三种正确写法，任选：

```powershell
# PowerShell（当前窗口）
cd D:\project\canvas-java

# 通用（跨盘符，cmd 和 PowerShell 都行）
cd D:\project\canvas-java

# 在 C 盘且要切到 D 盘时，PowerShell 建议加 -LiteralPath
Set-Location -LiteralPath 'D:\project\canvas-java'
```

判断自己在哪个 shell：提示符是 `PS C:\...>` 就是 PowerShell，是 `C:\...>` 就是 cmd。**`/d` 只在后者有效。**

**坑二：`docker compose` 报 `no configuration file provided`**

这个报错 100% 是**当前目录不对**——`docker-compose.yml` 在后端工程根目录 `D:\project\canvas-java` 下，不在前端仓库里。用 `pwd`（PowerShell）或 `cd`（不带参数，cmd）确认当前路径，再执行。

### 4. 实测验证结果

```bash
# ① 健康检查
$ curl -s http://127.0.0.1:8081/api/v1/health
{"code":0,"message":"success","data":{"app":"canvas-server","version":"0.0.1",
"status":"UP","db":{"status":"UP","latencyMs":1},"redis":{"status":"UP","latencyMs":1},
"checkedAt":"2026-11-11T22:00:23"},"traceId":"cec6d4180e3f4878a247765580f4febc"}

# ② 404 统一响应（注意前导斜杠）
$ curl -s http://127.0.0.1:8081/api/v1/not-exist
{"code":40400,"message":"接口不存在: /v1/not-exist","traceId":"75cbf14093a44b1b93709704827489db"}

# ③ traceId 透传
$ curl -s -D - -o /dev/null -H "X-Trace-Id: my-test-001" http://127.0.0.1:8081/api/v1/health | grep -i x-trace-id
X-Trace-Id: my-test-001

# ④ Swagger
swagger-ui/index.html → 200
v3/api-docs          → 200

# ⑤ 库自动创建
$ docker exec canvas-mysql mysql -uroot -proot -e "SHOW DATABASES LIKE 'infinite_canvas';"
infinite_canvas
$ docker exec canvas-mysql mysql -uroot -proot -e "SELECT @@version, @@character_set_server;"
8.0.46   utf8mb4
```

### 5. 两个日志里的"假故障"

启动日志里这两条**都不是错误**，不用管：

| 日志 | 原因 |
| --- | --- |
| `No MyBatis mapper was found in '[com.infinitecanvas.canvas]' package` | mapper 包现在是空的。阶段 2 写第一个 Mapper 并加 `@MapperScan` 后消失 |
| 停掉应用后 Maven 报 `MojoExecutionException` | Ctrl+C 或 kill 掉 java 进程后 Maven 的正常反应，不是代码问题 |

## 七、本阶段要掌握的知识点

建议按这个顺序看，每条都能在代码里找到对应实现：

1. `@SpringBootApplication` 到底做了什么（组件扫描 + 自动配置 + 配置类）。
2. 为什么 `context-path=/api` + `@RequestMapping("/v1/health")` 最终是 `/api/v1/health`。
3. `record` 写 DTO/VO 的好处，以及 Jackson 如何序列化 record。
4. `@RestControllerAdvice` 的生效时机，为什么它能接住 Controller 抛出的异常。
5. 为什么业务异常用 `warn`、未知异常用 `error`。
6. 过滤器（Filter）和拦截器（Interceptor）的区别，为什么 traceId 必须放 Filter。
7. MDC 是什么，为什么它跟线程绑定，异步线程里为什么会丢。
8. MyBatis-Plus 三个拦截器分别解决什么问题，为什么顺序不能乱。
9. 为什么 Redis 的 value 不用默认的 JDK 序列化。
10. `spring.profiles.active` 的分层配置怎么覆盖：`application.yml` 打底，`application-dev.yml` 覆盖。

更细的设计动机见 [design.md](./design.md)。

## 八、常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| PowerShell 报 `Set-Location : 找不到接受实际参数"..."的位置形式参数` | 在 PowerShell 里用了 cmd 的 `cd /d` | 去掉 `/d`：`cd D:\project\canvas-java` |
| `No plugin found for prefix 'spring-boot'` | 当前目录不是 Maven 工程（在 `C:\Users\xxx` 下执行了 mvn） | 先切到 `canvas-java` 目录，确认目录里有 `pom.xml` |
| `no configuration file provided` | 同上，不在 `docker-compose.yml` 所在目录 | 先切到 `canvas-java` 目录 |
| `docker compose up` 长时间无输出 | 正在拉镜像，compose 不显示进度条 | 另开窗口 `docker images \| grep mysql` 看是否在长 |
| 拉镜像反复 `Retrying in N seconds` | 某层下载中断自动重试 | 正常，**不要 Ctrl+C**，重试会复用已下载部分 |
| 启动报 `Port 8081 was already in use` | 端口被占 | `netstat -ano \| findstr 8081` 找到 PID 后 `taskkill /PID xxx /F`，或改 `server.port` |
| 访问 8081 返回 Spring 默认错误 JSON（`"error":"Internal Server Error"`） | 8081 上跑的是别的服务，不是本项目 | 看响应里有没有 `traceId` 字段，没有就不是本项目 |
| 启动报 `Access denied for user 'root'` | 连到了本机 3306 的 MySQL 而不是容器 | 确认 `application-dev.yml` 的端口是 **3307** |
| 启动报 `Unknown database 'infinite_canvas'` | 容器还在初始化，库还没建好 | 等 `docker compose ps` 显示 `healthy` 后再起应用 |
| `Cannot connect to the Docker daemon` | Docker Desktop 没启动 | 开始菜单点 Docker Desktop，等托盘图标 running |
| `Bind for 0.0.0.0:3307 failed: port is already allocated` | 3307 / 6380 被占 | `netstat -ano \| findstr 3307` 查占用，或改 compose 里的宿主端口并同步改 yml |
| 启动报 `Communications link failure` | MySQL 没起来或端口不对 | 先 `docker compose ps` 看健康状态，再核对 `application-dev.yml` 的 host/port |
| `redis.status` 是 DOWN，错误 `Unable to connect` | Redis 没起来，或连的是本机 6379 的 Memurai | 容器方案端口是 **6380** |
| Swagger 打开 404 | 路径少了 context-path | 正确地址是 `/api/swagger-ui.html` |
| 控制台中文乱码 | Windows 终端编码 | IDEA 里 Settings → File Encodings 全设 UTF-8，或忽略（接口返回正常） |
| 日志文件里没有 traceId | 用了非请求线程（如定时任务） | 正常，`TraceId.current()` 会返回 `-` |

## 九、下一阶段

**阶段 1：账号体系**

- 建 `t_user` 表
- 注册 / 登录 / 刷新 token / 登出
- BCrypt 密码、JWT 签发、Refresh Token 存 Redis
- Spring Security 过滤链，无状态鉴权
- 同时把 CORS 迁到 Security 配置里

做完后所有业务接口都会要求带 token，`/health` 保持匿名可访问。
