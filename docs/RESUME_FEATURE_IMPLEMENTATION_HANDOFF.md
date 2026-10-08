# LearnHub 简历功能生产化交接文档

> 用途：将本文完整提交给新的开发对话，作为后续实现任务的上下文、实施顺序与验收依据。
>
> 项目目录：`D:\Project\myproject\LearnHub`

## 1. 项目背景

LearnHub（问课尚学）是一个在线职业教育平台，核心功能包括课程学习、课程购买、点赞收藏、问答交流、优惠券营销、文件存储和后台课程管理。

技术栈：

- Spring Boot
- Spring Security
- MyBatis-Plus
- MySQL
- Redis
- Lua
- RabbitMQ
- Redisson
- Elasticsearch
- Vue 3 / TypeScript
- 阿里云 OSS

本轮开发目标不是继续增加普通页面，而是让代码真正支撑以下简历描述：

1. 高并发秒杀：Lua 校验、Redis 预扣、RabbitMQ 异步下单、Redisson 分布式锁和最终一致性。
2. 点赞系统：Redis Set 幂等、RabbitMQ 异步聚合、定时批量刷库和最终一致性。
3. 认证系统：Spring Security、RS256 JWT 双令牌、Redis Refresh Token 白名单、即时撤销。
4. 搜索系统：Elasticsearch 原生检索、标签过滤、`function_score` 融合 BM25 与业务权重、`completion suggester` 联想。

## 2. 开发约束

### 2.1 Git 约束

- 大功能必须从最新 `master` 新建分支。
- 分支命名规则：`feature/xxx`。
- 不要直接在 `master` 上开发。
- 开始前检查工作区，不要覆盖或回退用户已有修改。
- 开发完成后只提交功能分支，由用户自行合并到 `master`。

建议按模块拆分分支，避免一次提交过大：

- `feature/seckill-consistency`
- `feature/async-like-pipeline`
- `feature/elasticsearch-search`
- `feature/auth-session-hardening`

### 2.2 配置约束

- 新增配置时，先在 `.env.example` 中添加不含真实凭据的示例项。
- 实际本地配置写入 `.env`。
- `.env` 必须保持在 `.gitignore` 中，不得提交。
- 不要删除现有可用的 OSS、MySQL、Redis、RabbitMQ 或 Elasticsearch 配置。

### 2.3 实现原则

- MySQL 是订单、领取记录、点赞记录和计数的最终事实源。
- Redis 用于高并发校验、缓存、计数和短期状态，不能成为唯一持久化位置。
- RabbitMQ 消费者必须具备数据库级幂等，不能只依赖 JVM 内存 Map。
- 禁止吞掉消息异常后仍返回业务成功。
- 关键业务需要补偿、对账、监控字段和可重复执行能力。
- 优先复用已有表和代码结构，不随意引入新依赖。
- 每个模块必须补充针对性测试，并在完成后执行后端测试及前端构建。

## 3. 当前真实实现状态

### 3.1 秒杀模块：部分实现

已有能力：

- `CouponService` 调用 `lua/coupon_seckill.lua`。
- Lua 原子完成库存检查、重复领取检查、`DECR` 和 `SADD`。
- 秒杀成功后可向 RabbitMQ 发送消息。
- `SeckillConsumer` 可以消费消息并调用确认逻辑。
- 确认阶段接入了 Redisson 分布式锁。
- `schema.sql` 已定义 `coupon`、`coupon_claim`、`seckill_order`、`mq_consume_log` 等表。

主要缺口：

- 优惠券和领取结果仍主要保存在 JVM `ConcurrentHashMap` 中。
- 没有真正写入 `seckill_order`、`coupon_claim` 和消费日志表。
- MQ 消息缺少全局唯一的请求 ID 或消息 ID。
- 没有数据库事务状态机和数据库唯一约束兜底。
- 没有 Publisher Confirm 业务回调、重试、死信处理、库存回补和定时对账。
- Redis 不可用时退化到本机 Map，多实例环境下不安全。

关键入口：

- `learnhub-server/src/main/java/com/learnhub/marketing/coupon/CouponService.java`
- `learnhub-server/src/main/java/com/learnhub/marketing/coupon/SeckillConsumer.java`
- `learnhub-server/src/main/java/com/learnhub/marketing/coupon/CouponMessagingConfiguration.java`
- `learnhub-server/src/main/resources/lua/coupon_seckill.lua`
- `sql/schema.sql`

### 3.2 点赞模块：部分实现

已有能力：

- Redis Set 实现点赞判重与幂等。
- 点赞事件可发送到 RabbitMQ。
- 消费者会将增量写入 Redis Hash。
- 课程点赞数量已经可以展示。

主要缺口：

- 消费者目前是逐条 `HINCRBY`，不是真正的批量聚合。
- 点赞请求仍同步调用 `synchronizeCourseCount()` 更新课程表，数据库写入没有脱离请求链路。
- 没有定时任务批量读取增量并刷入数据库。
- `like_record` 表没有真正接入业务。
- 没有可靠消息、失败重试、补偿和对账机制。
- 本地 Map 降级不能保证多实例一致性。

关键入口：

- `learnhub-server/src/main/java/com/learnhub/interaction/like/LikeService.java`
- `learnhub-server/src/main/java/com/learnhub/interaction/like/LikeEventConsumer.java`
- `learnhub-server/src/main/java/com/learnhub/interaction/like/LikeMessagingConfiguration.java`
- `sql/schema.sql`

### 3.3 认证模块：基本实现

已有能力：

- Spring Security 无状态认证。
- Access Token 与 Refresh Token 双令牌。
- JWT 使用 RS256 签名和验签。
- Access Token 默认 15 分钟，Refresh Token 默认 7 天。
- Refresh Token JTI 写入 Redis 白名单。
- 刷新时使用 `getAndDelete` 一次性消费旧 Refresh Token并轮换。
- Access Token 注销后进入 Redis denylist。
- 修改密码通过 `tokenVersion` 使旧令牌失效。

剩余问题：

- 前端普通退出目前没有把 Refresh Token 传给后端，导致后端撤销 Refresh Token 的能力没有被完整使用。
- 生产环境必须配置固定、可持久化的 RSA 密钥，不能依赖运行时临时生成密钥对。
- 缺少会话列表、按设备撤销等增强能力，但这不是当前简历描述的必需项。

关键入口：

- `learnhub-server/src/main/java/com/learnhub/config/SecurityConfig.java`
- `learnhub-server/src/main/java/com/learnhub/auth/security/JwtAuthenticationFilter.java`
- `learnhub-server/src/main/java/com/learnhub/auth/security/JwtTokenService.java`
- `learnhub-server/src/main/java/com/learnhub/auth/service/AuthService.java`
- `learnhub-server/src/main/java/com/learnhub/auth/controller/AuthController.java`
- `learnhub-web/src/api/index.ts`
- `learnhub-web/src/stores/auth.ts`

### 3.4 搜索模块：部分实现

已有能力：

- 已引入 Spring Data Elasticsearch。
- 已定义课程 ES Document 和 Repository。
- 支持关键词匹配和标签过滤。
- 当前存在手写相关性分数和点赞热度加权。

主要缺口：

- 当前通常先读取所有文档，再通过 Java Stream 过滤和排序。
- 没有使用 ES 原生 `bool`、`multi_match` 和 `filter` 查询。
- 没有真正使用 `function_score` 融合 BM25 与点赞权重。
- Document 没有 `completion` 类型字段。
- 没有真正的 Elasticsearch `completion suggester`。
- 缺少正式的全量重建、增量同步和索引版本切换方案。

关键入口：

- `learnhub-server/src/main/java/com/learnhub/search/CourseSearchDocument.java`
- `learnhub-server/src/main/java/com/learnhub/search/CourseSearchRepository.java`
- `learnhub-server/src/main/java/com/learnhub/search/CourseSearchService.java`
- `learnhub-server/src/main/java/com/learnhub/search/CourseSearchController.java`
- `docs/PRODUCTION_EVOLUTION_HANDOFF.md`

## 4. 推荐实施顺序

按依赖和简历价值，建议依次完成：

1. 秒杀持久化与最终一致性。
2. 点赞异步聚合与定时刷库。
3. Elasticsearch 原生搜索和联想。
4. 认证退出链路和密钥配置加固。

每个阶段应独立开发、测试、提交，不要同时大范围改动四个模块。

## 5. 阶段一：秒杀生产化

### 5.1 目标链路

```text
客户端请求
  -> 生成 requestId
  -> Lua 原子校验库存/用户并预扣
  -> 创建 PENDING 秒杀请求或可靠消息记录
  -> RabbitMQ
  -> 消费者数据库幂等校验
  -> MySQL 事务扣减库存 + 写 coupon_claim/seckill_order
  -> SUCCESS
  -> 失败时重试；不可恢复时回补 Redis 并标记 FAILED
```

### 5.2 数据层

检查并完善以下表：

- `coupon`
- `coupon_claim`
- `seckill_order`
- `mq_consume_log`

要求：

- `coupon_claim` 对 `(coupon_id, user_id)` 设置唯一约束。
- `seckill_order.request_id` 唯一。
- 消费日志以 `message_id` 或 `request_id` 唯一。
- 库存扣减 SQL 必须带 `available_stock > 0` 条件。
- 状态至少包含 `PENDING`、`SUCCESS`、`FAILED`。

补充对应的 Entity、Mapper 和 Service，不再以 JVM Map 作为最终存储。

### 5.3 消息可靠性

- 消息必须包含 `requestId`、`couponId`、`userId`、时间和必要的版本信息。
- 消费者先进行数据库幂等检查。
- 数据库事务内完成库存扣减、领取记录、订单状态和消费日志。
- 配置有限次数重试与死信队列。
- Publisher Confirm 失败时必须记录并可补发。
- 不要把 Redisson 锁当作最终一致性的唯一保证；数据库条件更新和唯一约束才是最终防线。

### 5.4 补偿与对账

- 新增库存回补 Lua，原子执行 `INCR` 与用户集合移除。
- 仅对确认失败且尚未成功落库的请求执行回补。
- 增加定时对账任务：比较 Redis 预扣状态、订单状态和数据库库存。
- 对账任务必须可重复执行且幂等。

### 5.5 秒杀验收标准

- 同一用户重复请求只产生一条领取记录和一条有效订单。
- 并发请求数量大于库存时，数据库成功订单数不得超过库存。
- 消息重复投递不会重复扣减数据库库存。
- 消费失败后可以重试；最终失败能够回补 Redis 库存。
- 服务重启后订单、领取记录和库存状态不丢失。
- Redis、RabbitMQ 短暂异常后能够通过补偿或对账恢复一致。
- 必须提供至少一个真实 Redis + RabbitMQ + MySQL 的集成测试或可重复执行的并发验证脚本。

## 6. 阶段二：点赞异步聚合

### 6.1 目标链路

```text
点赞/取消点赞请求
  -> Redis Set 原子判重和状态变更
  -> 发送 like event
  -> RabbitMQ 消费者聚合 Redis 增量
  -> 定时任务批量 claim 增量
  -> MySQL 批量更新 like_record 和目标 like_count
  -> 失败批次恢复或重试
```

### 6.2 Redis 数据结构

至少明确以下结构及生命周期：

- 目标点赞用户集合：用于判重和查询用户点赞状态。
- 待刷库增量 Hash：字段为目标类型与目标 ID，值为正负增量。
- 刷库处理中 Hash 或批次键：避免任务执行中丢失数据。

读取增量时不能直接读取后清空。推荐使用 Lua 或重命名批次键的方式原子 claim 当前批次，成功后删除，失败后恢复或重试。

### 6.3 数据库落库

- 正式使用 `like_record` 保存用户点赞关系。
- 使用唯一约束保证同一用户对同一目标只有一条有效关系。
- 定时任务批量更新课程或问答的 `like_count`。
- 从请求链路删除同步 `synchronizeCourseCount()` 数据库更新。
- 需要定义取消点赞时记录删除或状态更新策略。

### 6.4 点赞验收标准

- 重复点赞不会重复计数。
- 点赞后接口可以立即从 Redis 返回正确状态和近实时计数。
- 正常请求链路不直接更新课程点赞数量字段。
- 定时任务可以将一个批次的增量一次性同步到数据库。
- 刷库任务重复执行不会重复累计。
- 刷库中途失败不会永久丢失增量。
- Redis 与数据库出现差异时存在可执行的对账修复方式。
- 增加 Redis/MQ 场景测试和定时刷库幂等测试。

## 7. 阶段三：Elasticsearch 原生搜索

### 7.1 索引设计

课程文档建议包含：

- `id`
- `title`
- `description`
- `instructorName`
- `tags`
- `likeCount`
- `status`
- `publishedAt`
- `suggest`：`completion` 类型

文本字段使用项目现有 IK analyzer；标签字段使用 `keyword`。

如 Spring Data 注解无法完整表达 mapping，可提供版本化 JSON mapping，并使用别名管理索引，例如：

- `learnhub-courses-v1`
- 别名 `learnhub-courses`

### 7.2 搜索查询

将 Java Stream 搜索替换为 ES 原生查询：

- `bool`
- `multi_match`
- 标签使用 `filter/terms`
- 只检索可发布课程
- `function_score` 或等价原生 DSL
- BM25 文本相关性为基础分
- 使用 `field_value_factor`、`log1p` 或脚本对 `likeCount` 加权
- 支持分页并限制最大分页深度

不要先调用 `findAll()` 再在应用层过滤。

### 7.3 联想建议

- 增加独立接口，例如 `GET /api/search/courses/suggestions?prefix=...`。
- 使用 ES `completion suggester`，不要使用 Java `startsWith` 模拟。
- 为课程标题、标签或讲师生成合理的 suggestion input。
- 对空前缀、重复结果和最大返回数量进行限制。

### 7.4 索引同步

- 课程创建、更新、发布、下架和点赞计数变化时同步 ES。
- 明确同步失败后的重试机制。
- 提供管理员全量重建接口或命令。
- 全量重建应使用新索引加 alias 切换，避免搜索中断。
- ES 不可用时应明确降级策略，不能静默返回不完整数据并伪装成功。

### 7.5 搜索验收标准

- 搜索请求不会加载全部 ES 文档或全部数据库课程。
- 标题强匹配结果优先于仅描述命中的结果。
- 相同文本相关性下，点赞较高课程获得合理加权但不能完全压过相关性。
- 标签过滤由 ES filter 执行。
- 联想接口实际执行 completion suggester。
- 课程变更后索引可以增量更新。
- 全量重建后别名能够无中断切换。
- 增加查询 DSL、排序、标签过滤、联想和索引同步测试。

## 8. 阶段四：认证链路加固

### 8.1 必做事项

- 前端退出时将当前 Refresh Token 一并发送给 `/auth/logout`。
- 后端撤销 Refresh Token 白名单记录，同时将当前 Access Token 加入 denylist。
- 无论后端退出请求成功或失败，前端最终都清理本地令牌；但需要正确记录或提示服务端退出失败。
- 保持刷新令牌一次性轮换语义。
- 确认生产配置使用固定 RSA 私钥和公钥文件。
- 不要把真实私钥提交到 Git。

### 8.2 认证验收标准

- 登录返回有效双令牌。
- 旧 Refresh Token 刷新一次后不能再次使用。
- 退出后原 Access Token 立即失效。
- 退出后原 Refresh Token 不能再换取新令牌。
- 修改密码后此前签发的 Access/Refresh Token 均不可继续使用。
- 服务重启后，只要 RSA 密钥未变化，已签发且未撤销的合法令牌仍可按规则验证。

## 9. 测试与验证要求

每个功能分支完成后至少执行：

```powershell
mvn -q test
npm run build
```

具体目录按当前项目结构执行。除此之外：

- 秒杀：增加并发、重复消息、消费失败和回补测试。
- 点赞：增加重复点赞、取消点赞、批量刷库、任务重入和失败恢复测试。
- 搜索：增加 Testcontainers 或真实 ES 集成测试，验证实际 DSL 行为。
- 认证：增加登录、刷新轮换、退出撤销和修改密码失效测试。

如果本地基础设施不足导致集成测试无法执行，必须：

1. 保留测试代码或验证脚本；
2. 明确列出需要启动的服务；
3. 报告哪些验证已完成、哪些尚未执行；
4. 不得把“编译通过”描述成“最终一致性已经验证”。

## 10. 完成定义

只有满足以下条件，才可以认为简历描述已经被代码真实支撑：

- 秒杀订单和领取记录已持久化到 MySQL，具备幂等消费、补偿和对账。
- 点赞数据库更新已移出请求链路，具备异步聚合、批量刷库和失败恢复。
- 搜索实际执行 ES 原生 `function_score`，联想实际使用 `completion suggester`。
- 退出链路同时撤销 Access Token 和 Refresh Token。
- 关键链路有自动化测试或可重复执行的真实基础设施验证证据。
- 文档、`.env.example`、数据库迁移或 `schema.sql` 已同步更新。
- 功能分支工作区干净，提交记录清晰，未提交 `.env` 或密钥文件。

## 11. 提交给新对话的执行提示

可将下面这段话和本文一起提交：

> 请先完整阅读 `docs/RESUME_FEATURE_IMPLEMENTATION_HANDOFF.md` 和现有 `docs/PRODUCTION_EVOLUTION_HANDOFF.md`，再检查当前 Git 状态及 `AGENTS.md`。不要直接在 master 上修改；从最新 master 创建符合 `feature/xxx` 规则的功能分支。先实施阶段一“秒杀生产化”，完成代码、数据库映射、消息幂等、失败补偿、对账与测试后再停止。不要只补接口骨架，也不要以 JVM Map 作为最终存储。完成后列出修改文件、提交号、测试结果和仍存在的风险，分支由用户自行合并。

