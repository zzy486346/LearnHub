# LearnHub 本次对话开发交接

> 整理日期：2026-10-08。项目：`D:\Project\myproject\LearnHub`。
> 本文根据本次对话、当前代码、Git 提交与专题文档整理。代码核对基线：`cd026d8`，当前分支：`feature/course-carousel`。
> 用途：供下一次开发对话快速接手。本文的“已完成”指相应实现已经落地，不代表所有生产级故障场景均已验收。

## 1. 总体结论

- 原始简历交接任务的阶段一（秒杀）、阶段二（点赞）、阶段三（ES 搜索）已有实现及测试代码。
- 本次追加的问答赞同、优惠券金额与个人券包、Kibana、IK、登录失效跳转、演示课程视频和课程轮播均已落地。
- 课程列表最终要求为每页 3 门、每 3 秒自动循环滚动、鼠标悬停暂停、不显示暂停按钮；无白屏切换修复保留。
- 阶段四认证链路加固尚未完整完成；搜索严格匹配、真实支付及优惠券核销等不能写成已完成。
- 原始 `RESUME_FEATURE_IMPLEMENTATION_HANDOFF.md` 是启动本轮工作的历史需求，不再代表最新实现状态。后续先读本文，再按模块查专题手册和代码。

## 2. 已完成事项

### 2.1 阶段一：秒杀持久化与一致性

对应提交：`7175d16`。

- Redis Lua 原子校验并预扣库存，使用带 hash tag 的优惠券 key。
- 秒杀订单、领取记录及消费日志接入 MySQL，不再将 JVM Map 作为最终事实源。
- 请求/消息标识、数据库唯一约束、条件扣库存和事务确认共同保证幂等。
- 接入 RabbitMQ 发布确认、重试与死信处理；通过补偿、预留记录和定时对账处理失败与库存恢复。
- 提供真实 MySQL、Redis、RabbitMQ 场景的 `SeckillInfrastructureIntegrationTest`，以及持久化和服务单元测试。

入口：`learnhub-server/src/main/java/com/learnhub/marketing/coupon/`、`learnhub-server/src/main/resources/lua/`。
升级与恢复说明：`docs/SECKILL_CONSISTENCY_RUNBOOK.md`。
迁移：`sql/migrations/V20260929__seckill_consistency.sql`。

### 2.2 阶段二：点赞异步聚合

对应提交：`cecdf50`。

- Redis Set 保证用户维度幂等，点赞与取消点赞事件进入异步处理链路。
- RabbitMQ 事件接入持久化 inbox、去重与关系序列处理，再批量落库点赞关系和目标计数。
- 批次记录、失败恢复、补偿和对账用于处理重复、乱序与聚合状态丢失。
- 点赞数据库计数更新移出普通点赞请求的同步链路；课程计数落库后生成 ES 同步任务。
- 提供服务、事件聚合、批量刷库、持久化与真实基础设施测试。

入口：`learnhub-server/src/main/java/com/learnhub/interaction/like/`。
迁移：`sql/migrations/V20260930__async_like_pipeline.sql`。

### 2.3 问答赞同与取消赞同

对应提交：`a3c87d6`。

- 问题、回答支持真实赞同/取消赞同，不再只是固定数字展示。
- 接入当前用户状态回显、计数更新、未登录处理及请求中交互保护。
- 复用点赞链路，并提供前端按钮与后端接口测试。

重点文件：`learnhub-web/src/components/VoteButton.vue`、`QuestionView.vue`、`QuestionDetailView.vue`（后两者位于 `src/views/`）。
前端验证：`npm run test:vote`。

### 2.4 优惠券领取、金额展示与个人券包

对应提交：`e6f43fc`。

- 修复秒杀领取失败相关接口/状态处理，区分异步受理、确认成功与失败。
- 展示优惠金额、使用门槛、库存和活动状态，避免只展示券名称。
- 个人中心接入已领取券、有效期、使用状态及待确认请求。
- 普通券与秒杀券提供重复领取、未开始、过期、售罄等业务反馈。
- 提供 `CouponWalletIntegrationTest` 和前端优惠券测试。

重点页面：`learnhub-web/src/views/CouponView.vue`、`ProfileView.vue`。
前端验证：`npm run test:coupon`。
边界：领取与查看不等于已实现下单抵扣或优惠券核销闭环。

### 2.5 协作约定与数据库中文注释

对应提交：`d70333c`、`4564c7d`、`1a31061`。

- 已写入根目录 `AGENT.md`：代理临时启动的后端测试进程必须在成功、失败或中断后清理；用户日常后端由用户启动，不能擅自重启或批量杀 Java 进程。
- 已规定表和每个字段必须具有 MySQL 中文 COMMENT；补注释不得改变字段类型、默认值、自增、可空等业务结构。
- 已提供既有数据库补注释迁移、注释清单及生成/一致性检查工具，并记录本轮补齐操作。
- 新环境使用最新 schema；已有数据卷仍需检查迁移执行情况，不能认为更新 SQL 文件等于数据库已升级。

入口：`AGENT.md`、`sql/schema.sql`、`sql/schema-comments.json`、`sql/generate-comments.mjs`。
迁移：`sql/migrations/V20261007__chinese_schema_comments.sql`。

### 2.6 阶段三：ES 原生搜索与可靠索引同步

对应提交：`9d9e24e`；实现说明：`aa89522`。

- ES 原生 `bool + multi_match + function_score`，BM25 文本相关性与有上限的点赞热度加分。
- 标题权重 6、讲师和标签权重 2、描述权重 1；热度额外加分上限 1。
- 标签与发布状态过滤、分页深度限制、真实 completion 联想及结果去重。
- 课程创建、修改、发布、下架及点赞落库与搜索任务在同一 MySQL 事务内提交。
- 持久化任务按课程合并、generation CAS、失败退避重试；全量重建新索引并原子切换 alias。
- ES 故障明确返回搜索不可用，不以内存/不完整数据库结果伪装成功。
- 提供查询、任务、同步单元测试及真实 ES/MySQL 集成测试。

入口：`learnhub-server/src/main/java/com/learnhub/search/`。
Mapping：`learnhub-server/src/main/resources/elasticsearch/courses-v1.json`。
迁移：`sql/migrations/V20261007__course_search_sync.sql`。
专题文档：`docs/SEARCH_ELASTICSEARCH_RUNBOOK.md`、`docs/ELASTICSEARCH_SEARCH_IMPLEMENTATION.md`。

### 2.7 Kibana 与中文 IK 插件

对应提交：`1e1140b`、`37ebc55`。

- Compose 添加与 ES 8.15.3 对应的 Kibana 8.15.3，容器内部连接 `http://elasticsearch:9200`，中文界面；本机入口 `http://localhost:5601`。
- ES 安装相同版本的 analysis-ik，索引使用 `ik_max_word`、查询使用 `ik_smart`。
- 插件保存在 Compose 指定的 `es-plugins` 卷，词典配置复制到插件目录，专题手册记录重建容器后持久化要求。
- 记录插件加载、分词与 mapping 验证方式；已有 standard 索引必须重建才能切换 IK。

边界：插件安装完成不意味着任意未来环境都已重建索引。换机器/数据卷后须核对 alias mapping；禁止用 `docker compose down -v` 代替升级。

### 2.8 README 与管理发布反馈

对应提交：`5c6df44`、`46e3202`。

- README 已重写为项目介绍，覆盖功能、技术架构、关键设计和工程结构，不介绍本地开发配置。
- 管理端增加草稿课程发布按钮与状态反馈。
- 运维和本地升级步骤集中在专题文档，不应再次堆入 README。

### 2.9 登录失效后跳转登录

对应提交：`e1e8bf1`。

- 登录失效统一由全局会话处理跳转登录，避免各页面弹出“加载失败”等误导性错误。
- 提供前端会话测试，课程搜索测试覆盖认证失效时不展示课程加载失败。
- 注意：这是会话失效用户体验修复，不代表阶段四退出双令牌撤销已经完成。

入口：`learnhub-web/src/api/`、`learnhub-web/src/stores/auth.ts`。
前端验证：`npm run test:auth`。

### 2.10 新增课程与动画讲解视频

对应提交：`d928195`。

- 新增 21 门演示短课、42 章、42 节配套视频；本次导入后原有 3 门保留，共 24 门。该数量是导入时快照，不是永远固定的课程总数。
- 课程脚本由 AI 辅助编写，视频由中文 Windows SAPI 配音、字幕、程序化示意图与转场合成；不是真人/数字人，也不是神经网络直接生成的视频。
- 视频共约 114 分钟、约 112 MiB，格式为 960×540、24 FPS、H.264/AAC MP4，支持 Range 与拖动播放。
- 清单包含封面、视频 URL 和真实时长；SQL 使用受控固定 ID、幂等新增和冲突保护，同时写入索引同步任务。
- 专题记录已导入当前数据库，21 条新课程搜索任务已处理。

媒体临时目录：`D:\Download\codex\learnhub-course-media`。
本地媒体地址：`http://localhost:8091`，Compose 的 `course-media` 服务位于 `demo` profile。
工具：`scripts/course-media/`；数据：`sql/demo-courses.sql`。
说明：`docs/COURSE_DEMO_CONTENT.md`。
边界：这是演示短课；字幕近似对齐，正式发布前需内容审校；删除临时目录会使视频失效。

### 2.11 每页三门与无白屏自动轮播

对应提交：`445cc45`、`44f6aba`、`7dc6b39`、`cd026d8`。

- 最终间隔为 3000 ms。曾使用两秒、五秒的提交属于历史版本，后续以三秒为准。
- 每页 3 门，自动下一页，末页回首页；保留手动前后翻页。
- 鼠标悬停暂停、移出恢复，不显示暂停按钮；键盘聚焦、页面隐藏及轮播区域不可见时也保护交互。
- 未完成请求期间保留当前课程，取消先退场再进场的 `out-in` 切换，进出卡片在同一网格位置并行滑动，避免短暂白屏。
- 自动翻页沿用已提交筛选，不把输入中的关键词草稿提前提交；卸载清理定时器与监听。
- 定时器在请求完成后安排下一轮，网络慢时实际翻页间隔会长于 3 秒；不是无视请求耗时的硬性时钟。

入口：`learnhub-web/src/views/CourseListView.vue`。
测试：`learnhub-web/scripts/course-search.test.mjs`。
最新验证：12 项搜索/轮播测试通过，`npm run build` 通过；保留现有大体积 chunk 警告。

## 3. 未完成及待验证事项

### 3.1 阶段四：退出双令牌撤销与生产密钥

当前前端 `authApi.logout()` 只 POST `/auth/logout`，未在请求体提交 Refresh Token；`auth.ts` 虽会在 finally 清理本地状态，但不能据此证明服务端旧 Refresh Token 已撤销。

后端 `AuthController` 和 `AuthService.logout(accessToken, refreshToken)` 已支持撤销双令牌；缺口主要是前端没有传入 Refresh Token，以及退出/刷新轮换的专项回归测试不足。已有 RS256、令牌时长、密码版本及前端会话失效测试不能代替这组验收。

当前两个 RSA 路径同时配置时读取固定 PEM；都未配置时会生成临时密钥并警告，只配置一个时启动失败。生产固定密钥的实际注入与持久化尚不能据此认定已验收。

后续需要：

1. 将当前 Refresh Token 随退出请求提交，使用后端既有撤销能力；无论请求结果都清理本地会话。
2. 覆盖退出后旧 Access/Refresh Token 均不可用、刷新轮换不可重放、修改密码后旧令牌失效。
3. 核对生产环境固定 RSA 密钥的注入、持久化和启动校验；不能依赖运行时临时密钥，也不能提交私钥。
4. 明确退出请求失败时的反馈，不得把本地清理等同于服务端撤销成功。

会话列表、按设备撤销属于扩展建议，不是已实现功能。

### 3.2 搜索“大模型应用开发入门”出现 Spring Boot 课程

已分析并记录原因，尚未实现严格匹配优化：当前 `multi_match` 默认 OR，不设置 AND 或最低匹配比例。长查询中的“应用”等通用词可命中其他课程描述；“大模型”查询不含该词，结果可能更少。这不是两份同 ID 的 ES 文档。

若继续处理，应先确定“召回更多”还是“限制弱相关”的产品目标，再用现有课程数据回归验证 AND、`minimum_should_match`、短语加权或通用词策略。不能把安装 IK 或提高标题权重写成已彻底解决弱相关结果。

### 3.3 业务闭环与生产化缺口

- 课程已有订单和访问控制基础，但 `CourseOrderService.pay()` 当前直接以 `ZERO_PAYMENT` 标记 PAID；未接入真实支付渠道、支付回调、退款和资金对账。
- 优惠券领取与券包已实现；购买时抵扣、并发核销、退款返券未形成闭环。
- 演示媒体仍依赖本机临时目录和 localhost URL；未迁入 OSS/CDN 生产资源流程。
- 视频转码、直播、内容审核、个性化推荐、完整运营管理与监控仍待建设。
- 搜索尚无同义词、高亮、纠错、搜索历史或旧索引自动清理；同步任务需监控积压。
- 管理界面和部分个人中心统计仍有完善空间，不能将 README 中项目边界描述为已全部补齐。
- 当前实现和既有集成测试不能代替多实例压力测试、跨进程崩溃恢复、长时间故障演练与生产安全验收。

## 4. 建议推进顺序

| 优先级 | 建议事项 | 验收重点 |
| --- | --- | --- |
| P0 | 完成阶段四退出双令牌撤销 | 服务端旧双令牌失效；本地清理；失败反馈；固定密钥重启验证 |
| P1 | 搜索相关性回归与策略优化 | 对比“大模型”和长标题查询，控制弱相关结果，不误伤标签筛选和联想 |
| P1 | 演示视频审校并迁移 OSS/CDN | 视频可播放/拖动、真实时长、权限与 URL 更新，不再依赖临时目录 |
| P1 | 一致性链路故障演练与监控 | 秒杀补偿、点赞批次、MQ 死信、索引任务积压、重启恢复有证据 |
| P2 | 真实支付和优惠券抵扣核销 | 支付幂等、回调鉴权、并发核销、退款与对账 |
| P2 | 前端性能及轮播体验 | 包体拆分、低网速和触屏行为、减少动态偏好的可访问性评估 |

轮播建议不得覆盖当前明确要求：三秒、每页三门、无暂停按钮。若调整可访问性或触屏策略，应作为单独产品变更处理。

## 5. 接手时的约束与验证

### 5.1 必须保留的约定

- 先读取根目录 `AGENT.md` 和用户当前提供的协作指令，再检查 `git status --short` 与实际分支。
- 本文整理时存在用户未跟踪文件 `docs/RESUME_FEATURE_IMPLEMENTATION_HANDOFF.md`；本次不覆盖、不加入提交。后续不要擅自删除或提交它。
- 大功能从实际最新主干建立功能分支，小改动保持可审查；完成验证后创建中文 Conventional Commit，不自动合并或推送。
- 日常后端由用户自行启动。代理仅清理自己启动的测试进程，不能按名称批量杀进程或重启用户服务。
- 新表和字段必须有中文 COMMENT；迁移保留既有字段属性，不清理用户业务数据。
- 不提交 `.env`、私钥、令牌、构建产物；不要用删除数据卷、重置种子数据来解决问题。

### 5.2 推荐复验入口

以下是可复验命令，不表示本文整理时重新执行了全部测试：

```powershell
# 项目根目录：后端单元测试和完整校验
D:\Android\apache-maven-3.9.10\bin\mvn.cmd test
D:\Android\apache-maven-3.9.10\bin\mvn.cmd verify
node sql/generate-comments.mjs --check
node --test scripts/course-media/catalog.test.mjs

# 前端目录
Set-Location D:\Project\myproject\LearnHub\learnhub-web
npm run test:auth
npm run test:vote
npm run test:coupon
npm run test:search
npm run test:admin
npm run build
```

真实基础设施测试需显式开启 `-Dlearnhub.it.enabled=true`，并先确认相应 MySQL、Redis、RabbitMQ、ES 和迁移就绪；具体命令见专题手册。测试类存在或默认 Maven 测试通过，不等于 opt-in 集成测试在当前环境已经执行。

本文整理没有启动后端、迁移数据库、重建索引或重新生成媒体；也没有重新检查所有容器的实时健康状态。最新三秒轮播测试与构建结果来自上一轮执行；更早模块的完成依据为提交、代码及既有专题记录，接手时应针对实际运行环境复验。

### 5.3 可以提交给下一对话的提示

> 请先阅读 `docs/CONVERSATION_HANDOFF_20261008.md`、根目录 `AGENT.md` 和当前工作区状态。阶段一至三已实现，不要重复推倒重做。优先完成阶段四退出双令牌撤销，先核对前端 logout 请求、后端撤销逻辑与密钥配置，然后增加针对性测试。保留三秒/每页三门/悬停暂停/无白屏轮播；不要覆盖用户未跟踪的原始交接文档，不要擅自重启日常后端，不要提交密钥或清理业务数据。完成后提供代码、验证证据、提交号和仍未完成的边界。
