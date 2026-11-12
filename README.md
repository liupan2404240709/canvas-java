# canvas-server

无限画布（infinite-canvas）的 Java 后端，Spring Boot 3 + MySQL 8 + Redis + MyBatis-Plus。

前端 `web/` 的页面保持不动，后端按页面功能逐步补齐：画布项目 → 素材库 → 提示词库 → 渠道配置 → 生成任务 → 对话助手。

## 技术栈

| 用途 | 选型 |
| --- | --- |
| 运行时 | JDK 17 |
| 框架 | Spring Boot 3.3.5（Web MVC） |
| 持久层 | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.0 |
| 缓存 | Redis 7（Lettuce 连接池） |
| 文档 | SpringDoc OpenAPI 3 |

## 目录结构

```
canvas-java/
├── docker-compose.yml           # MySQL + Redis
├── pom.xml
└── src/main
    ├── java/com/infinitecanvas/canvas
    │   ├── CanvasApplication.java
    │   ├── common/              # 统一响应、错误码、异常、常量
    │   ├── config/              # MyBatis-Plus、Redis、CORS、OpenAPI
    │   ├── controller/          # 只做参数接收与结果组装
    │   ├── service/             # 业务逻辑
    │   ├── vo/                  # 出参
    │   └── infra/               # 过滤器、MyBatis 处理器等基础设施
    └── resources
        ├── application.yml      # 公共配置
        ├── application-dev.yml  # 本地连接信息
        └── logback-spring.xml   # 日志（含 traceId）
```

## 快速开始

> Shell 提示：下面命令在 **PowerShell** 和 **cmd** 里都能用（注意 `cd` 后面**不要加 `/d`**，那是 cmd 专用语法，PowerShell 会报错）。

```powershell
cd D:\project\canvas-java

# 1. 先手动启动 Docker Desktop，等托盘图标 running，然后：
docker compose up -d
docker compose ps

# 2. 两个容器都 healthy 后，启动应用
mvn spring-boot:run

# 3. 另开一个窗口验证
curl http://127.0.0.1:8081/api/v1/health
```

首次 `docker compose up -d` 要拉 `mysql:8.0`（约 600MB）和 `redis:7-alpine`，国内网络实测 MySQL 需要 10~20 分钟，中途出现 `Retrying in N seconds` 属正常，**不要中断**。

| 服务 | 宿主机端口 | 说明 |
| --- | --- | --- |
| canvas-server | 8081 | 8080 留给机器上其他 Java 项目 |
| MySQL | 3307 | 容器内 3306，避开本机已有 MySQL |
| Redis | 6380 | 容器内 6379，避开本机已有 Memurai/Redis |

库 `infinite_canvas` 由 compose 自动创建，账号 `root/root`，与 `application-dev.yml` 一致，无需手动建库。

- 接口文档：http://127.0.0.1:8081/api/swagger-ui.html
- 日志文件：`logs/canvas-server.log`

## 文档

阶段说明、设计文档、接口文档在本工程的 `docs/` 目录：

- `plan.md`：整体改写规划与进度
- `stage-00-scaffold/`：阶段 0（环境与骨架）的说明 / 设计 / 接口文档

> 本工程独立于前端仓库 `infinite-canvas-main`，两者平级放在 `D:\project\` 下。
