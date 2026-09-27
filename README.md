# 问课尚学（LearnHub）

问课尚学是一个面向职业学习者的在线教育平台。MVP 已覆盖“注册登录 → 浏览/搜索课程 → 互动问答与点赞 → 领取优惠券”的核心链路，并提供课程与优惠券的最小管理入口。

> 当前版本用于展示模块化单体的业务设计与关键并发方案；支付、直播、视频转码、复杂审核和推荐系统不在 MVP 范围内。

## 功能概览

- 用户认证：Spring Security + RS256 JWT 双令牌，Access Token 15 分钟、Refresh Token 7 天，支持刷新轮换和退出撤销。
- 课程学习：课程分页、详情与学习进度记录，前端提供课程发现、详情和个人学习中心。
- 互动社区：问题发布、回答、列表浏览；点赞使用 Redis Set 判重并通过 RabbitMQ 异步聚合。
- 优惠券：普通领取与限量秒杀；Lua 原子校验/预扣 Redis 库存，RabbitMQ 异步削峰。
- 课程搜索：Elasticsearch 关键词检索、标签过滤、业务热度排序与联想建议。
- 最小管理端：课程和优惠券管理页面入口，便于后续扩展完整 CRUD 与权限控制。
- 工程化：统一响应与异常处理、MyBatis-Plus、Swagger UI、Actuator、Docker Compose 中间件编排。

## 技术架构

```text
Vue 3 + TypeScript + Vite
             │ /api
             ▼
Spring Boot 3 / Spring Security / MyBatis-Plus
       │           │           │           │
     MySQL       Redis      RabbitMQ   Elasticsearch
   事实数据   会话/库存/幂等   异步削峰      搜索投影
```

项目采用模块化单体：MySQL 是业务事实源，Redis 承载短期高性能状态，Elasticsearch 索引可从事实数据重建，RabbitMQ 消费端按至少一次投递设计幂等。

## 目录结构

```text
LearnHub/
├─ learnhub-common/        # 通用响应、异常与基础契约
├─ learnhub-pojo/          # 可复用的数据对象模块
├─ learnhub-server/        # Spring Boot API、领域服务与测试
├─ learnhub-web/           # Vue 3 用户端与最小管理端
├─ sql/                    # MySQL 表结构和演示数据
├─ deploy/                 # Docker Compose 与 Nginx 配置
├─ docs/                   # MVP 架构与实现设计
├─ .env.example            # 后端环境变量示例
└─ pom.xml                 # Maven 聚合工程
```

## 环境要求

- JDK 17
- Maven 3.9.10（本机约定路径：`D:\Android\apache-maven-3.9.10\bin\mvn.cmd`）
- Node.js 20+ 与 npm 10+
- Docker Desktop / Docker Compose（推荐用于启动 MySQL、Redis、RabbitMQ、Elasticsearch）

中间件默认端口：MySQL `3306`、Redis `6379`、RabbitMQ `5672`（管理台 `15672`）、Elasticsearch `9200`。请确认本机端口未被占用。

## 快速启动

### 1. 启动中间件

```powershell
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml ps
```

首次创建 MySQL 数据卷时会自动执行 `sql/schema.sql` 和 `sql/seed.sql`。若数据卷已存在，初始化脚本不会重复执行。

### 2. 配置 JWT 密钥

后端使用 RS256，私钥不能提交到 Git。生成本地密钥后，把路径通过环境变量传入：

```powershell
New-Item -ItemType Directory -Force .local/keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local/keys/private.pem
openssl rsa -pubout -in .local/keys/private.pem -out .local/keys/public.pem
$env:LEARNHUB_JWT_PRIVATE_KEY_PATH = "file:$((Resolve-Path .local/keys/private.pem).Path)"
$env:LEARNHUB_JWT_PUBLIC_KEY_PATH = "file:$((Resolve-Path .local/keys/public.pem).Path)"
```

`.local/` 应保持在 Git 忽略范围内。也可使用安全的密钥挂载或密钥管理服务注入路径。

### 3. 启动后端

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd spring-boot:run -pl learnhub-server -am
```

- API 根地址：`http://localhost:8080/api`
- Swagger UI：`http://localhost:8080/swagger-ui.html`
- 健康检查：`http://localhost:8080/actuator/health`

### 4. 启动前端

```powershell
cd learnhub-web
Copy-Item .env.example .env.local
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 会把 `/api` 代理到 `http://localhost:8080`。

## 配置说明

后端配置均可通过环境变量覆盖，完整样例见根目录 `.env.example`：

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `LEARNHUB_DB_URL` | `jdbc:mysql://localhost:3306/learnhub...` | MySQL JDBC 地址 |
| `LEARNHUB_DB_USERNAME` | `learnhub` | 数据库用户名 |
| `LEARNHUB_DB_PASSWORD` | `learnhub` | 数据库密码（仅本地默认） |
| `LEARNHUB_REDIS_HOST` | `localhost` | Redis 主机 |
| `LEARNHUB_RABBITMQ_HOST` | `localhost` | RabbitMQ 主机 |
| `LEARNHUB_ES_URIS` | `http://localhost:9200` | Elasticsearch 地址 |
| `LEARNHUB_JWT_PRIVATE_KEY_PATH` | classpath 占位路径 | RSA 私钥资源位置 |
| `LEARNHUB_JWT_PUBLIC_KEY_PATH` | classpath 占位路径 | RSA 公钥资源位置 |

前端变量位于 `learnhub-web/.env.local`：

```dotenv
VITE_API_BASE_URL=/api
```

任何真实密码、令牌或私钥都不得提交到仓库。

## 主要 API

接口基础路径为 `/api`，所有业务接口返回统一结构 `{ code, message, data, timestamp }`，详情以 Swagger 为准。

| 模块 | 方法与路径 | 说明 |
| --- | --- | --- |
| 认证 | `POST /api/auth/register` | 注册 |
| 认证 | `POST /api/auth/login` | 获取 Access/Refresh Token |
| 认证 | `POST /api/auth/refresh` | 轮换刷新令牌 |
| 认证 | `POST /api/auth/logout` | 撤销当前会话 |
| 课程 | `GET /api/courses` | 分页查询课程 |
| 课程 | `GET /api/courses/{id}` | 查询课程详情 |
| 进度 | `GET/PUT /api/learning/progress/{courseId}` | 查询/幂等更新课程进度 |
| 问答 | `GET/POST /api/questions` | 查询/发布问题 |
| 问答 | `POST /api/questions/{id}/answers` | 发布回答 |
| 点赞 | `PUT/DELETE /api/likes/{type}/{targetId}` | 点赞/取消点赞 |
| 优惠券 | `GET /api/coupons` | 查询优惠券 |
| 优惠券 | `POST /api/coupons/{id}/claim` | 普通领取 |
| 秒杀 | `POST /api/coupons/{id}/seckill` | 秒杀领取 |
| 搜索 | `GET /api/search/courses` | 关键词和标签搜索 |
| 联想 | `GET /api/search/courses/suggest` | 前缀建议 |

需要认证的请求使用：

```http
Authorization: Bearer <access-token>
```

前端 Axios 响应拦截器会在 `401` 时合并并发刷新请求，刷新成功后重放原请求；刷新失败则清理本地令牌并跳转登录页。

## 高并发设计摘要

### 优惠券秒杀

1. Redis Lua 在单次原子操作中校验活动、库存和用户是否重复领取，并预扣库存。
2. 请求进入 RabbitMQ，由消费者异步创建领取记录，削平数据库瞬时写流量。
3. 数据库唯一约束和消费幂等抵御重复消息；Redisson 锁只包围必要的最终确认临界区。
4. 失败消息可重试，Redis 状态与 MySQL 事实数据可通过补偿任务对账。

### 异步点赞

Redis Set 以“用户 + 目标”判重，点赞事件进入 RabbitMQ 后聚合增量，降低热点计数写压力；消费者和定时落库按幂等、可重试设计，最终以 MySQL 数据完成对账。

### 搜索

Elasticsearch 使用 BM25 相关性并融合点赞热度等业务权重，标签作为过滤条件；联想使用前缀查询/Completion 思路提供低延迟建议。Elasticsearch 只保存可重建投影，不作为业务事实源。

### 本地开发降级

部分中间件适配在服务不可用时提供内存降级路径，目的是让本地单元测试和核心接口演示不依赖完整基础设施。该路径不具备跨实例一致性、持久化或生产级并发保证，**仅限本地开发与测试**；生产环境必须连接 Redis、RabbitMQ、MySQL 和 Elasticsearch，并启用对应健康检查与告警。

## 验证命令

后端测试与完整校验：

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd test
D:\Android\apache-maven-3.9.10\bin\mvn.cmd verify
```

前端类型检查与生产构建：

```powershell
cd learnhub-web
npm install
npm run build
```

基础设施状态与服务健康：

```powershell
docker compose -f deploy/docker-compose.yml ps
Invoke-RestMethod http://localhost:8080/actuator/health
```

## 开发约定

- 修改前后检查 `git status --short`，保护并行开发者的工作。
- 每个完成并验证的逻辑修改都必须立即创建 Conventional Commits 风格的 Git commit。
- 不提交密钥、密码、本地配置、IDE 文件和构建产物。
- 后端 Controller 只处理协议与校验，业务规则放在 Service，Mapper 只负责持久化。
- 并发链路必须覆盖重复请求、消息重投、消费失败、缓存丢失和服务重启场景。

更完整的范围、数据模型和一致性方案见 [`docs/MVP_IMPLEMENTATION.md`](docs/MVP_IMPLEMENTATION.md)。
