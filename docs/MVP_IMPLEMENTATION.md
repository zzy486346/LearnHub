# 问课尚学（LearnHub）MVP 实现文档

## 1. 文档目标

本文定义 LearnHub 从空仓库到可演示 MVP 的实现范围、架构、数据与接口契约、关键高并发方案、开发顺序和验收标准。首版目标不是一次完成完整商业平台，而是交付一条可运行、可测试的学习闭环，并用最小业务场景证明 Redis、RabbitMQ、Elasticsearch、Lua 和分布式锁方案可行。

MVP 成功标准：用户能够注册登录，浏览和搜索课程，查看课时并记录进度，对课程或问答点赞、收藏，在问答区提问和回答，领取普通或秒杀优惠券；管理员能够创建并发布课程和优惠券。

## 2. 已知约束与参考项目结论

- 项目名称：问课尚学（LearnHub）。
- Java：JDK 17。
- Maven：`D:\Android\apache-maven-3.9.10\bin\mvn.cmd`（3.9.10）。
- 后端参考：`D:\work\java\code\sky-take-out`。
  - 已确认是 Maven 多模块项目，根目录下包含 `sky-common`、`sky-pojo`、`sky-server` 与 `sql`。
  - LearnHub 借鉴其模块边界，不直接复制外卖领域代码。
- 前端参考：`D:\Download\Java-take-out\nginx-1.20.2`。
  - 这是 Vue + TypeScript + Element UI 的构建产物，不是完整源码工程。
  - 可参考单页应用布局、懒加载分包、Axios API 层、token 路由守卫、空状态组件及 Nginx `/api` 反向代理模式。
  - LearnHub 必须新建源码工程与 `package.json`，不得直接在 Nginx 构建产物上继续开发。

## 3. MVP 范围

### 3.1 必须交付

1. 用户注册、登录、双令牌刷新、退出和即时撤销。
2. 课程分类、课程列表、课程详情、章节课时和学习进度。
3. 课程收藏；课程、问题、回答的点赞与取消点赞。
4. 问题发布、问题列表/详情、回答发布。
5. 普通优惠券领取，以及一个限量优惠券秒杀场景。
6. 课程全文搜索、标签过滤、业务权重排序和前缀联想。
7. 管理端最小能力：课程、课时、标签、优惠券的创建、编辑与发布。
8. OpenAPI 文档、数据库脚本、本地中间件编排、核心自动化测试。

### 3.2 暂不纳入 MVP

- 在线支付、退款、发票、结算。
- 视频转码、DRM、对象存储直传；MVP 只保存可访问的媒体 URL。
- 直播课、IM、复杂审核流、多租户。
- 复杂推荐系统；首页使用编辑推荐和热度排序。
- 优惠券叠加、满减分摊和复杂风控。
- 微服务拆分、分布式事务框架和独立数据仓库。

## 4. 总体架构

首版采用“模块化单体 + 独立中间件”。它比微服务更适合从零构建和本地调试，同时保留清晰的领域边界，后续可按压力拆分搜索、互动和营销服务。

```text
Vue 3 Web
    |
 Nginx /api
    |
Spring Boot API (learnhub-server)
    |-- MyBatis-Plus --> MySQL（事实数据）
    |-- Redisson/Lettuce --> Redis（会话、幂等、库存、聚合）
    |-- RabbitMQ（秒杀与点赞削峰）
    `-- Elasticsearch（课程搜索与联想索引）
```

一致性原则：MySQL 是业务事实源；Redis 是高性能状态和聚合层；Elasticsearch 是可重建的检索投影；RabbitMQ 消息必须允许重复投递，消费者必须幂等。

## 5. 建议目录

```text
LearnHub/
├─ pom.xml
├─ AGENT.md
├─ README.md
├─ learnhub-common/
│  └─ src/main/java/com/learnhub/common/
├─ learnhub-pojo/
│  └─ src/main/java/com/learnhub/pojo/
├─ learnhub-server/
│  ├─ src/main/java/com/learnhub/
│  │  ├─ auth/
│  │  ├─ course/
│  │  ├─ interaction/
│  │  ├─ marketing/
│  │  ├─ search/
│  │  └─ infrastructure/
│  └─ src/test/
├─ learnhub-web/
│  ├─ src/api/
│  ├─ src/views/
│  ├─ src/components/
│  └─ src/stores/
├─ sql/
│  ├─ schema.sql
│  └─ seed.sql
├─ deploy/
│  ├─ docker-compose.yml
│  └─ nginx.conf
└─ docs/
   └─ MVP_IMPLEMENTATION.md
```

建议根 Maven 工程统一依赖和插件版本；`learnhub-common` 不依赖业务模块，`learnhub-pojo` 只承载数据结构，`learnhub-server` 组合并运行应用。避免为了形式拆出过多模块。

## 6. 技术基线

| 层级 | 选择 | 用途 |
| --- | --- | --- |
| 后端 | Spring Boot 3.x、Java 17 | Web、配置、任务调度 |
| 安全 | Spring Security、JWT RS256 | 认证、鉴权、双令牌 |
| 持久化 | MyBatis-Plus、MySQL 8 | 核心业务数据 |
| 缓存 | Redis、Redisson | 会话、库存、集合幂等、分布式锁 |
| 消息 | RabbitMQ | 秒杀削峰、点赞事件异步化 |
| 搜索 | Elasticsearch 8 | BM25 检索、过滤、排序、联想 |
| API | Jakarta Validation、springdoc-openapi | 校验与接口文档 |
| 测试 | JUnit 5、Mockito、Testcontainers | 单元与集成验证 |
| 前端 | Vue 3、TypeScript、Vite、Pinia、Vue Router、Axios、Element Plus | 用户端和最小管理端 |

具体版本在创建根 `pom.xml` 与 `package.json` 时集中锁定，不在子模块中散落定义。

## 7. 核心数据模型

所有业务表统一包含 `id BIGINT`、`created_at DATETIME(3)`、`updated_at DATETIME(3)`；需要逻辑删除的表增加 `deleted TINYINT`。时间统一以 UTC 存储，接口返回 ISO 8601。

### 7.1 用户与认证

- `user`：`username`、`password_hash`、`nickname`、`avatar_url`、`status`、`token_version`。
- `user_role`：用户与 `USER`/`ADMIN` 角色关系。
- 刷新令牌白名单保存于 Redis；MySQL 仅在需要安全审计时记录会话元数据，不保存明文令牌。

约束：`user.username` 唯一；密码使用 BCrypt 或 Argon2 单向哈希。

### 7.2 课程学习

- `course_category`：分类名称、排序、状态。
- `course`：标题、副标题、封面、简介、讲师、状态、价格、点赞数、收藏数、发布时间。
- `course_chapter`：课程、标题、排序。
- `course_lesson`：章节、标题、媒体 URL、时长、试看标记、排序。
- `tag`、`course_tag`：标签及课程关联。
- `learning_progress`：用户、课时、播放位置、是否完成、最后学习时间。

关键索引：课程状态与发布时间联合索引；进度表 `(user_id, lesson_id)` 唯一。

### 7.3 互动

- `favorite_record`：用户、目标类型、目标 ID；`(user_id, target_type, target_id)` 唯一。
- `like_record`：最终点赞事实，可用于对账；字段同收藏表并带状态。
- `question`：用户、课程、标题、内容、状态、点赞数、回答数。
- `answer`：问题、用户、内容、状态、点赞数、采纳标记。

MVP 的 `target_type` 仅允许 `COURSE`、`QUESTION`、`ANSWER`，业务层必须校验目标存在。

### 7.4 优惠券与秒杀

- `coupon`：名称、类型、门槛、优惠金额、总库存、可用库存、领取起止时间、使用起止时间、每人限领数、状态、版本号。
- `coupon_claim`：优惠券、用户、状态、领取时间；MVP 每人每券限领一张，`(coupon_id, user_id)` 唯一。
- `seckill_order`：请求 ID、优惠券、用户、状态、失败原因；`request_id` 和 `(coupon_id, user_id)` 分别唯一。
- `mq_consume_log`：消息 ID、消费者名、处理状态，用于关键消息幂等和排障。

## 8. API 草案

统一前缀 `/api/v1`，统一响应结构：`{ code, message, data, traceId }`。业务错误使用稳定错误码，HTTP 状态码表达协议语义。

### 8.1 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/auth/register` | 用户注册 |
| POST | `/auth/login` | 返回访问令牌和刷新令牌 |
| POST | `/auth/refresh` | 轮换刷新令牌并返回新令牌对 |
| POST | `/auth/logout` | 撤销当前会话 |
| POST | `/auth/logout-all` | 撤销用户全部会话 |
| GET | `/users/me` | 当前用户资料 |

### 8.2 课程与学习

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/courses` | 分页、分类、标签和排序筛选 |
| GET | `/courses/{id}` | 课程、章节和统计信息 |
| GET | `/lessons/{id}` | 课时播放信息 |
| PUT | `/lessons/{id}/progress` | 幂等更新学习进度 |
| GET | `/users/me/progress` | 当前用户学习记录 |
| PUT | `/favorites/{targetType}/{targetId}` | 收藏 |
| DELETE | `/favorites/{targetType}/{targetId}` | 取消收藏 |

### 8.3 点赞与问答

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| PUT | `/likes/{targetType}/{targetId}` | 点赞，重复调用仍成功 |
| DELETE | `/likes/{targetType}/{targetId}` | 取消点赞，重复调用仍成功 |
| GET | `/questions` | 按课程、关键词、最新/热度查询 |
| POST | `/questions` | 提问 |
| GET | `/questions/{id}` | 问题及回答分页 |
| POST | `/questions/{id}/answers` | 回答 |

### 8.4 优惠券、搜索和管理

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/coupons/available` | 可领取券列表 |
| POST | `/coupons/{id}/claim` | 普通领取 |
| POST | `/coupons/{id}/seckill` | 秒杀请求，返回请求 ID |
| GET | `/seckill-orders/{requestId}` | 查询异步处理结果 |
| GET | `/search/courses` | 关键词、标签、分页搜索 |
| GET | `/search/suggestions?prefix=` | 前缀联想 |
| POST/PUT | `/admin/courses/**` | 课程管理 |
| POST/PUT | `/admin/coupons/**` | 优惠券管理 |

## 9. 认证系统设计

### 9.1 令牌模型

- Access Token：JWT，RS256，有效期 15 分钟，声明包含 `sub`、`jti`、`roles`、`sid`、`tokenVersion`、`iat`、`exp`。
- Refresh Token：可使用带随机高熵 `jti` 的 RS256 JWT，有效期 7 天，只允许在刷新接口使用。
- 私钥通过环境变量或挂载文件注入；仓库只保留公钥示例或生成说明。

Redis 约定：

```text
auth:refresh:{sid}         -> refresh jti 摘要、userId、tokenVersion，TTL 7 天
auth:access:deny:{jti}     -> 1，TTL 为 access token 剩余时长
auth:user:sessions:{uid}   -> sid Set，TTL 至最长会话到期
```

### 9.2 登录、刷新和撤销

1. 登录成功创建 `sid`，写入刷新令牌白名单，再返回令牌对。
2. 刷新时验证签名、用途、过期时间和 Redis 白名单；使用 Lua 比较并原子替换旧 `jti`，实现一次性轮换。
3. 退出时删除 `auth:refresh:{sid}`，并把当前 access `jti` 加入 denylist。
4. 全部退出时递增用户 `token_version`，删除用户所有会话；访问校验对比缓存版本，完成即时撤销。
5. 刷新令牌重放时撤销该 `sid`，记录安全日志并要求重新登录。

## 10. 高并发优惠券秒杀

### 10.1 Redis Key

```text
seckill:coupon:{couponId}:stock       -> 可用库存
seckill:coupon:{couponId}:users       -> 已成功预占的 userId Set
seckill:request:{requestId}           -> PENDING/SUCCESS/FAILED，TTL 24 小时
lock:seckill:{couponId}:{userId}      -> Redisson 锁
```

### 10.2 Lua 原子预扣

请求携带或服务端生成唯一 `requestId`。Lua 在同一哈希槽中完成：

1. 判断用户是否已在领取集合；已存在返回“重复领取”。
2. 判断库存是否大于 0；不足返回“已售罄”。
3. `DECR` 库存并 `SADD` 用户，记录请求为 `PENDING`。
4. 返回预占成功。

生产部署若采用 Redis Cluster，相关 key 使用统一 hash tag，如 `{coupon:123}`，保证脚本可原子执行。

### 10.3 消息与消费

预占成功后发送 `learnhub.seckill.order` 消息，包含 `messageId`、`requestId`、`couponId`、`userId`、时间戳。交换机、队列和消息均持久化，发布端开启 publisher confirm。

消费者处理流程：

1. 以 `messageId + consumer` 检查消费幂等。
2. 获取用户-优惠券粒度 Redisson 锁。
3. 检查 `coupon_claim` 唯一记录。
4. 使用 `UPDATE coupon SET available_stock = available_stock - 1 WHERE id = ? AND available_stock > 0` 做数据库最终防线。
5. 在同一事务创建 `coupon_claim`、更新 `seckill_order` 和消费日志。
6. 事务提交后更新 Redis 请求状态并 ACK。

可重试异常进入延迟重试队列；超过上限进入死信队列并告警。确定失败时执行幂等补偿 Lua：只有尚未补偿的请求才能 `INCR` 库存并 `SREM` 用户。定时对账比较 MySQL 有效领取数与 Redis 库存，异常时以 MySQL 重建 Redis。

## 11. 点赞系统

### 11.1 写入路径

```text
like:members:{targetType}:{targetId}  -> userId Set
like:delta:{shard}                    -> targetKey => 增量
like:event:dedup:{eventId}            -> 1，短期去重
```

1. 点赞接口用 Lua 执行 `SADD`；仅在返回 1 时发布 `LIKE +1` 事件。
2. 取消点赞用 Lua 执行 `SREM`；仅在返回 1 时发布 `LIKE -1` 事件。
3. RabbitMQ 消费者按目标哈希分片，通过 pipeline 将增量累计到 `like:delta:{shard}`，避免所有目标竞争单一热点 key。
4. 定时任务批量取出增量，使用数据库 `count = count + delta` 原子更新，并更新 `like_record` 最终事实。
5. 采用“读取并转移到 processing hash—落库—确认删除”的两阶段方式，服务崩溃后可恢复，不能直接 `GET` 后 `DEL`。

读接口返回 `数据库计数 + 尚未落库增量`，并根据 Set 判断当前用户是否已点赞。周期对账可用 Set 基数修正计数漂移。

### 11.2 幂等边界

- API 幂等：Redis Set 的 `SADD/SREM` 返回值。
- 消息幂等：`eventId` 去重；重复事件不重复累计。
- 落库幂等：每批有 `batchId`，数据库记录已应用批次。
- 数据库兜底：`like_record` 用户与目标联合唯一。

## 12. 搜索系统

### 12.1 索引字段

索引 `learnhub_course_v1`：

- `title`、`subtitle`、`description`：`text`，使用适合中文的分词器；生产前明确插件安装策略。
- `title.keyword`：精确匹配与聚合。
- `tags`、`categoryId`、`status`：`keyword`。
- `likeCount`、`favoriteCount`、`studentCount`：数值字段。
- `publishedAt`：日期。
- `suggest`：`completion`，输入由标题、讲师名和标签生成。

使用版本化索引和别名 `learnhub_course_read` / `learnhub_course_write`，支持无停机重建。

### 12.2 查询与排序

先用 `bool` 查询组合：

- `multi_match` 检索标题、副标题、简介，标题权重最高。
- `filter` 限制已发布状态、分类和标签，不影响相关性分数。
- `function_score` 在 BM25 上叠加 `log1p(likeCount)`、`log1p(studentCount)` 与发布时间衰减。

业务权重应设置上限，避免热门但不相关的课程压过高相关结果。所有参数通过配置管理，并用一组固定查询样本做排序回归测试。

### 12.3 数据同步

课程发布/更新事务提交后发送索引事件；消费者按 `courseId + version` 幂等更新。消息失败进入重试/死信队列。提供管理员“重建全量索引”命令，确保 ES 丢失时可从 MySQL 恢复。

联想使用 completion suggester，限制返回数量并去重；前端输入增加 200～300 ms 防抖。

## 13. 前端 MVP

### 13.1 页面

- `/login`、`/register`：认证。
- `/`、`/courses`：首页和课程筛选。
- `/courses/:id`：课程详情、章节、收藏、点赞。
- `/learn/:lessonId`：课时播放占位与学习进度。
- `/questions`、`/questions/:id`：问答列表、详情和回复。
- `/coupons`：优惠券列表、普通领取和秒杀状态轮询。
- `/profile`：学习记录、收藏和已领券。
- `/admin/**`：课程与优惠券的最小表单管理。

### 13.2 工程约定

- Axios 请求拦截器附加访问令牌；401 时同一时间只允许一个刷新请求，其余请求排队重放。
- 刷新失败清理会话并跳转登录；不在日志或错误弹窗中暴露令牌。
- 页面和路由使用动态导入；共享 `Layout`、`Header`、`EmptyState`、`LoadingState`、`Pagination`。
- Pinia 只保存会话和必要的跨页状态；服务端数据优先由 API 层管理。
- Nginx 将 `/api/` 转发到 Spring Boot，SPA 路由使用 `try_files $uri $uri/ /index.html`。

## 14. 配置与本地运行

`deploy/docker-compose.yml` 应提供 MySQL、Redis、RabbitMQ、Elasticsearch；应用和前端可先在宿主机运行以便调试。配置按 `local`、`test`、`prod` 分层。

环境变量示例：

```text
LEARNHUB_DB_URL
LEARNHUB_DB_USERNAME
LEARNHUB_DB_PASSWORD
LEARNHUB_REDIS_HOST
LEARNHUB_RABBITMQ_HOST
LEARNHUB_ES_URIS
LEARNHUB_JWT_PRIVATE_KEY_PATH
LEARNHUB_JWT_PUBLIC_KEY_PATH
```

仓库提交 `.env.example`，忽略 `.env`、真实密钥、IDE 文件、`target/`、`node_modules/` 和前端构建目录。

## 15. 可观测性与错误处理

- 每个请求生成 `traceId`，在 HTTP 响应、日志和 MQ 消息中透传。
- 使用结构化日志，记录接口耗时、消息 ID、业务 ID和结果，不记录密码与完整令牌。
- 暴露 Actuator 健康检查；分别检查 MySQL、Redis、RabbitMQ 与 Elasticsearch。
- 关键指标：登录失败率、刷新重放、秒杀 Lua 结果分布、MQ 堆积/重试/死信、点赞待同步增量、ES 索引延迟。
- 全局异常处理区分校验失败、未认证、无权限、资源不存在、冲突、限流与系统错误。

## 16. 测试策略

### 16.1 单元测试

- JWT 签发、校验、过期、tokenVersion 与 denylist。
- 刷新令牌轮换和重放检测。
- 优惠券时间窗、领取限制和状态机。
- 搜索查询构建与业务权重边界。
- 点赞事件的正负增量和批次幂等。

### 16.2 集成测试

使用 Testcontainers 启动 MySQL、Redis、RabbitMQ、Elasticsearch，至少覆盖：

- 100 个并发请求争抢 10 份库存，最终成功恰好 10 条且同一用户不重复。
- MQ 重复投递不会重复创建领取记录或重复扣库存。
- 消费者事务失败后消息可重试，最终状态可查询。
- 重复点赞只产生一次有效增量，重复取消同理。
- 刷新令牌只能成功使用一次，退出后 access token 立即失效。
- 课程发布后最终可搜索，标签过滤和联想结果正确。

### 16.3 前端验证

- 登录、自动刷新、刷新失败退出。
- 课程列表到学习页的主路径。
- 点赞/收藏快速重复点击的状态一致性。
- 秒杀 `PENDING -> SUCCESS/FAILED` 状态展示。
- 空数据、网络错误、权限不足和移动端基础布局。

## 17. 实施里程碑

### M0：工程基线

建立 Maven 多模块、Vue 工程、Docker Compose、数据库脚本、统一响应/异常、CI 基础命令。完成条件：一条命令启动中间件，后端健康检查和前端首页可访问。

### M1：认证与课程闭环

完成用户、RS256 双令牌、课程/章节/课时、学习进度和最小管理端。完成条件：管理员发布课程，用户登录后完成一次学习进度更新。

### M2：互动系统

完成收藏、问答、Redis Set 点赞、RabbitMQ 聚合与定时落库。完成条件：重复点赞幂等，消息重投不造成计数漂移。

### M3：营销与秒杀

完成普通领取、Lua 预扣、RabbitMQ 下单、Redisson 锁、补偿与对账。完成条件：并发测试无超卖、无重复领取，失败请求可追踪。

### M4：搜索与交付加固

完成 ES 索引、function score、completion suggester、全量重建、关键监控和端到端测试。完成条件：固定查询集通过，索引可从 MySQL 重建，全部验收场景有证据。

## 18. MVP 验收清单

- [ ] 使用 JDK 17 与指定 Maven 能完成 `mvn test` 和 `mvn verify`。
- [ ] 本地环境可复现，README 无需隐含步骤即可启动。
- [ ] Access Token 为 15 分钟、Refresh Token 为 7 天，支持轮换与即时撤销。
- [ ] 秒杀并发验证中数据库无负库存、无超卖、无同用户重复领取。
- [ ] 点赞重复请求和 MQ 重投均保持幂等，计数最终与事实记录一致。
- [ ] 搜索支持关键词、标签过滤、相关性与业务权重融合，以及前缀联想。
- [ ] MySQL 数据可重建 Redis 秒杀状态和 Elasticsearch 索引。
- [ ] 密钥、密码、令牌和本地环境文件未进入 Git。
- [ ] OpenAPI、SQL、测试、部署配置与实现一致。
- [ ] 每个逻辑修改均已按照 `AGENT.md` 创建 Git commit，工作区干净。

## 19. 主要风险与控制

| 风险 | 控制措施 |
| --- | --- |
| Redis 预扣成功但消息未送达 | publisher confirm、请求状态、补偿任务和对账 |
| MQ 重投造成重复写 | 消息 ID、消费日志、数据库唯一索引、幂等状态机 |
| 点赞计数丢失 | processing 中间态、批次记录、Set 基数对账 |
| JWT 泄露后无法立即失效 | access denylist、tokenVersion、刷新白名单与轮换 |
| ES 与 MySQL 不一致 | 版本化事件、重试/死信、全量重建、延迟指标 |
| 过早微服务化拖慢交付 | 模块化单体起步，以压测和团队规模作为拆分依据 |

本文是 MVP 实施基线。后续需求变更必须同步更新范围、接口、数据迁移、测试和验收条目，并按 `AGENT.md` 单独提交。
