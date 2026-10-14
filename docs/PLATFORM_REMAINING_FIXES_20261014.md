# LearnHub 剩余问题修复与本机验收

- 验收日期：2026-10-14。
- 分支：`feature/platform-remaining-fixes`，基于已合并 P0/P1 修复的 `master` 提交 `0dab158`。
- 范围：[平台审查清单](PLATFORM_REVIEW_20261011.md)中的 R11～R13、G01、G03，以及用户确认的 G02 本机演示闭环。
- 真实支付渠道和商户凭据尚未提供；本记录不代表真实收费、支付回调或生产环境验收。

## 修复结果

| 编号 | 结果与规则 |
| --- | --- |
| R11 | 点赞与课程收藏写入前校验目标存在、未删除和可访问性。问题需 OPEN，回答需 VISIBLE 且父问题 OPEN；下架课程仅已购用户可交互。无效请求在 Redis、MQ、数据库写入前拒绝，合法重复操作保持幂等；历史失效收藏仍能取消。 |
| R12 | 新课程只能保存为草稿；发布要求启用的分类、至少一个章节、每章至少一个课时、正时长与可用视频。已发布课程新增章节或课时需先下架；删除课时不能破坏发布完整性。旧 HTTP(S) 视频地址仅检查格式，实际外部地址可用性仍取决于来源。 |
| R13 | 秒杀列表读取 Redis 可领取库存；缓存缺失或不可用时显示 UNAVAILABLE 并禁止领取，不以延迟的数据库库存伪装可领取。普通券仍读取数据库库存。 |
| G01 | 可设置优惠金额、门槛及使用期；本人有效券可参与下单，优惠不超过原价。下单占用、支付核销、取消和超时释放、退款返还与订单变化在同一事务中处理。到期返还券在钱包中显示过期，不再次可用。 |
| G02（本机） | 显式 LOCAL_DEMO 配置下可演示确认购买、取消和退款。优惠后应付金额用于计算，实际付款固定为 0 元；退款撤销已购权益。默认 DISABLED，prod/production profile 即使误配 LOCAL_DEMO 也拒绝演示支付和退款。 |
| G03 | 后台可编辑、下架课程，编辑章节与课时，上传封面，管理分类、标签并绑定课程。全部课程维持每页 10 条；公开课程列表支持动态分类和标签筛选，搜索按 categoryId 精确过滤。 |

分类、标签及优惠券领取记录的雪花 ID 以字符串传给前端，避免超过 JavaScript 安全整数范围后丢失精度。分类创建、课程回显、编辑、标签绑定与搜索参数均保持原值。

封面保存稳定的 `/api/courses/{id}/cover` 地址，由接口解析当前媒体访问地址并返回带 `Cache-Control: no-store` 的重定向，避免把过期签名地址长期存入课程。标签改名、课程编辑、分类或标签绑定变化按现有搜索任务机制同步。

## 订单与优惠券的状态约束

订单状态为 PENDING、PAID、CANCELLED、CLOSED、REFUNDED；优惠券领取记录为 AVAILABLE、RESERVED、USED，并按使用期展示 UPCOMING 或 EXPIRED。用户只能操作自己的订单与领取记录。

课程购买采用“课程→订单→领取记录”的锁顺序；取得领取记录锁后重新检查订单超时和券有效期。支付发现超时返回 CLOSED 并提交关闭、释放事务，详情页与个人中心不会误报解锁。退款后重新购买或待支付订单更换为无券，明确清空旧券关联及付款、取消、退款时间，避免 MyBatis 忽略空值留下旧状态。

现有 `(user_id, course_id)` 唯一约束继续保留：取消或退款后重新下单复用原行，并生成新的订单号。这是本机闭环的实现边界，不能作为真实交易的不可变历史账本。正式收费还需独立订单尝试、回调验签、金额核对、支付渠道退款及对账设计。

## 数据库与配置

新增可重复执行的迁移：

- [课程管理迁移](../sql/migrations/V20261012__course_management.sql)：封面媒体关联、标签与绑定时间字段、分类名称唯一索引。已有重复分类名时迁移拒绝建立唯一索引，不自动删除或合并数据。
- [订单优惠券迁移](../sql/migrations/V20261012__commerce_closure.sql)：订单金额与支付模式、关闭和退款时间、券占用及核销关联。历史已支付订单标记 LEGACY_DEMO，历史待支付订单回填到期时间；不改原实付金额或业务状态。

迁移更新状态注释时保留原类型、字符集、排序规则、是否可空、默认值及额外属性；历史订单回填显式保留原 `updated_at`。全新环境使用同步维护的 `sql/schema.sql`。

2026-10-14 已升级本机 Compose 的 `deploy-mysql-1 / learnhub` 数据库，两份迁移各执行两次。升级前备份涉及的 6 张表，逐列核对原属性和原索引，按原字段计算数据摘要并比较；课程 24 条、分类 3 条、标签 59 条、绑定 66 条、订单 3 条、领取记录 2 条均保持原数据，原时间字段未改变，中文注释完整。备份及前后结构证据位于被忽略的 `.local/platform-upgrade-20261014-141105`。

未重新启动用户日常后端。新后端加载后，搜索同步会检测旧 alias 是否缺少 `categoryId: long`，从 MySQL 重建并原子切换，保留旧索引；也可使用已有管理员重建接口。不要删除业务索引或数据卷代替升级。

`.env.example` 提供 `SPRING_PROFILES_ACTIVE=local`、`LEARNHUB_PAYMENT_MODE=LOCAL_DEMO`、`LEARNHUB_ORDER_PENDING_TIMEOUT=PT30M` 示例。本机被忽略的 `.env` 已补充本地模式设置，未提交密钥或本地配置。生产部署应使用 prod/production profile 并保持支付 DISABLED，直到真实渠道实现。

## 验证与复现

以下容器脚本使用 PowerShell 7，要求 Docker 已启动且本机已有所需镜像。仅对当前进程设置 Docker context，不修改全局 context。

```powershell
Set-Location D:\Project\myproject\LearnHub
$env:DOCKER_CONTEXT = 'default'
pwsh -NoProfile -File scripts\seckill\verify-infrastructure.ps1 -TestClass LikeInfrastructureIntegrationTest
pwsh -NoProfile -File scripts\admin\verify-course-management.ps1
pwsh -NoProfile -File scripts\commerce\verify-infrastructure.ps1
& D:\Android\apache-maven-3.9.10\bin\mvn.cmd test
& D:\Android\apache-maven-3.9.10\bin\mvn.cmd verify
```

真实 ES 专项使用随机独立 alias/index，不修改日常 alias；本机 ES 需已加载匹配的 IK 插件：

```powershell
& D:\Android\apache-maven-3.9.10\bin\mvn.cmd -pl learnhub-server -am '-Dtest=CourseSearchInfrastructureIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.it.enabled=true' test
```

前端全部回归与构建：

```powershell
Set-Location D:\Project\myproject\LearnHub\learnhub-web
node --test scripts/auth-session.test.mjs scripts/vote-button.test.mjs scripts/coupon.test.mjs scripts/commerce-flow.test.mjs scripts/course-search.test.mjs scripts/admin-course.test.mjs scripts/learning-flow.test.mjs
npm run build
```

已取得的专项记录：

| 验证 | 实际结果 |
| --- | --- |
| 交互目标 MySQL / Redis / RabbitMQ | 3 项执行通过：无效目标零副作用、失效收藏取消、异步点赞去重与缓存丢失恢复、批次重放和互斥。 |
| 后台专项测试 | 43 项通过。 |
| 后台隔离 MySQL | 6 项执行通过；新库与旧库重复迁移、中文注释、索引和原数据保真检查通过。 |
| 真实 Elasticsearch | 4 项执行通过：IK、关键词标签分类筛选、别名切换、旧映射自愈；仅操作随机自有索引。 |
| 订单与优惠券专项测试 | 24 项通过。 |
| 订单与优惠券隔离 MySQL | 9 项执行通过，覆盖购买退款重购、切换无券、支付取消竞争、同券跨课程竞争、锁等待跨到期、超时释放、重复下单、状态漂移回滚；新旧库迁移各执行两遍。 |
| 优惠券接口权限回归 | 3 项通过：公开查询、未登录或普通用户创建被拒绝且无服务写入、管理员合法创建。 |
| 前端全量 | 70 项通过，含大 ID 往返、个人中心及详情过期支付反馈；Vue 类型检查与 Vite 构建通过。 |
| 静态检查 | 注释字典与 schema 一致：22 张表、212 个字段；`git diff --check` 通过。 |

最终 Maven `test`、`verify` 均为 BUILD SUCCESS：各统计 181 项，145 项执行通过、36 项 opt-in 跳过，失败与错误均为 0。本轮单独启用了其中的 22 项真实基础设施测试（交互 3、后台 6、搜索 4、交易 9），全部通过；其余 14 项 opt-in 测试本轮未重新执行。默认跳过不能代替真实验收，生产 OSS 仍未验收。

各隔离容器按本次随机名称和归属标签清理，真实 ES 测试随机索引已清理，测试上下文与 JVM 已退出，没有代理启动的后端服务驻留。只为本轮验证恢复了本机 Docker，保留原日常中间件；未重启用户日常后端。全量日志位于被忽略的 `.local/remaining-all-test-20261014.log` 和 `.local/remaining-all-verify-20261014.log`。本轮完成文档命令路径、相对链接与 Markdown 围栏检查。

## 提交与边界

- `efdd290`、`6ad1131`：交互目标校验与隔离基础设施验收。
- `290e3f0`：秒杀展示库存同步。
- `fa17450`、`f23621e`、`623bb6c`：后台与交易前端、ID 精度、演示付款和超时反馈。
- `778dfcf`：后台管理、发布约束、分类标签与搜索筛选。
- `c10b95f`：本机订单优惠券闭环、并发约束与安全迁移。
- `e5638be`：同步新增字段和状态的中文注释字典。
- `7f262d5`：更新优惠券创建的合法测试请求，保留权限和金额校验。

本轮没有连接真实支付渠道、操作生产环境或进行真实 OSS 验收，也未进行浏览器端到端验收或负载压测。保留既有前端大分包提示。代码保存在功能分支，未自动合并或推送。
