# LearnHub 平台整体审查与补漏清单

- 审查日期：2026-10-11
- 审查基线：`master`，提交 `186febf`
- 文档性质：问题记录与后续验收依据，不代表修复计划已经执行。
- 原始审查状态：本清单记录 2026-10-11 基线问题，当时未修改功能代码、数据库或部署配置。
- 2026-10-14 更新：R01～R10 的本机修复已合并主干，详见[高优先级验收记录](PLATFORM_P0_P1_FIXES_20261012.md)。R11～R13、G01、G03 的修复及 G02 本机演示闭环已在 `feature/platform-remaining-fixes` 实现；验收证据与边界见[剩余问题修复记录](PLATFORM_REMAINING_FIXES_20261014.md)。G02 真实支付及退款对账仍待商户环境，真实 OSS 验收仍待部署环境。下文保留原始问题与验收要求，不代表当前版本仍存在全部问题。

## 1. 范围与结论

审查覆盖登录与会话、课程浏览、订单与学习、点赞收藏、优惠券秒杀、媒体访问和管理后台，重点检查权限边界、异步请求、状态流转与业务闭环。

平台主要流程已经具备，但仍有普通用户创建优惠券、学习进度不可靠、跨账号请求隔离不足等问题。建议先处理权限与数据一致性，再补齐后台维护及真实交易闭环。

证据分为三类：

- **模拟复现**：用现有前端函数在内存中构造响应顺序或失败，不连接真实接口、不写入业务数据。
- **静态确认**：根据代码分支、权限配置和数据库约束确认；尚未执行对应端到端或并发验证。
- **条件风险／产品缺口**：依赖部署配置或业务规则，不能视为当前部署已经发生的问题。

本轮没有执行全量测试、浏览器端到端验收、并发压测或真实 OSS 验证，也没有重新启动服务。既有测试通过记录不能替代以下问题的专项验收。

## 2. 优先级总览

P0 表示优先处理的权限问题；P1 表示影响账号隔离、学习、订单或重要状态一致性的问题；P2 表示数据质量和产品操作缺口。条件风险的实际优先级需结合部署配置确认。

| 编号 | 优先级 | 事项 | 证据类型 |
| --- | --- | --- | --- |
| R01 | P0 | 普通登录用户可创建优惠券 | 静态确认 |
| R02 | P1 | 直接切换账号时旧请求与新会话隔离不足 | 静态确认 |
| R03 | P1 | 学习进度保存失败仍提示完成 | 模拟复现 |
| R04 | P1 | 学习完成状态可回退、首次并发保存冲突 | 静态确认 |
| R05 | P1 | 快速切换课程时旧响应覆盖新页面 | 模拟复现 |
| R06 | P1 | 下架、已购访问和待支付订单规则不一致 | 静态确认／业务规则待明确 |
| R07 | P1 | 秒杀失败补偿后的重试与唯一约束冲突 | 静态确认 |
| R08 | P1 | 逻辑删除用户仍可能通过认证 | 静态确认／条件触发 |
| R09 | P1（条件） | OSS 公共域名配置绕过签名访问分支 | 配置风险 |
| R10 | P1（条件） | 上传完成校验后对象仍可能被覆盖 | 静态确认／待 OSS 验证 |
| R11 | P2 | 点赞收藏缺少目标有效性校验 | 静态确认 |
| R12 | P2 | 无内容课程可直接发布 | 静态确认／产品规则待明确 |
| R13 | P2 | 秒杀展示库存与预扣库存不同步 | 静态确认 |
| G01 | 产品闭环 | 优惠券使用、核销与退款返还未打通 | 产品缺口 |
| G02 | 产品闭环 | 真实支付及退款对账尚未完成 | 已知演示边界 |
| G03 | 产品维护 | 后台编辑、下架及分类等操作缺失 | 产品缺口 |

## 3. 问题详情与验收要求

### R01：普通登录用户可创建优惠券

- **触发与影响**：普通用户调用 `POST /api/coupons` 即可创建并激活优惠券，秒杀券还会初始化库存。接口没有管理员校验；安全配置只对 `/api/admin/**` 强制管理员身份。
- **依据**：[CouponController.java](../learnhub-server/src/main/java/com/learnhub/marketing/coupon/CouponController.java)、[SecurityConfig.java](../learnhub-server/src/main/java/com/learnhub/config/SecurityConfig.java)、[CouponService.java](../learnhub-server/src/main/java/com/learnhub/marketing/coupon/CouponService.java)。
- **建议**：将创建操作纳入管理员权限边界，不能仅隐藏前端入口。
- **验收**：未登录用户被拒绝；普通用户被拒绝且不产生数据库或 Redis 写入；管理员创建成功。保留合法的公开查询能力。

### R02：直接切换账号时旧请求与新会话隔离不足

- **触发与影响**：登录状态下可直接进入登录页并登录另一账号，`saveTokens` 不更新会话代次。旧账号请求收到 401 后可能使用新账号令牌重试；成功响应没有会话代次检查，旧用户资料响应可能覆盖新会话状态。跨标签页令牌替换也缺少完整处理。
- **依据**：[http.ts](../learnhub-web/src/api/http.ts)、[auth.ts](../learnhub-web/src/stores/auth.ts)、[router/index.ts](../learnhub-web/src/router/index.ts)。
- **边界**：此前退出登录、清空令牌及刷新失败的修复不等同于覆盖本问题；这里关注直接账号替换与迟到的成功响应。
- **建议**：区分新登录与令牌刷新；新登录或跨标签页账号替换时更新会话代次，阻止旧请求重试及旧响应更新状态。
- **验收**：构造 A 的请求延迟返回，在登录 B 后分别返回 200、401；不得展示 A 的资料，也不得把 A 发起的操作重试为 B 的操作。

### R03：学习进度保存失败仍提示完成

- **触发与影响**：播放结束后保存请求失败，保存函数捕获错误后正常结束，结束事件的后续逻辑仍弹出完成提示，用户误以为进度已持久化。
- **依据**：[LessonView.vue](../learnhub-web/src/views/LessonView.vue) 中 `queueProgressSave` 与 `handleEnded`。
- **复现结果**：注入保存请求失败后，`saveState` 为 `error`，仍收到“本课时已完成，可以继续下一课”的成功提示。
- **建议**：明确返回保存结果，成功后再提示完成；失败保留待保存进度并提供重试。
- **验收**：失败时不显示成功提示；重试成功后数据库与界面一致；离开页面时明确展示尚未保存的状态。

### R04：学习完成状态可回退、首次并发保存冲突

- **触发与影响**：服务端直接使用请求中的 `completed` 覆盖状态，较晚到达的 `false` 可覆盖已保存的 `true`。首次并发保存采用先查后插，可能同时查到无记录，再触发 `(user_id, lesson_id)` 唯一键冲突。页面关闭保存还可能绕过原有保存队列。
- **依据**：[LearningProgressService.java](../learnhub-server/src/main/java/com/learnhub/course/service/LearningProgressService.java)、[LessonView.vue](../learnhub-web/src/views/LessonView.vue)、[schema.sql](../sql/schema.sql)。
- **建议**：采用原子写入或处理插入冲突；完成状态保持单向变化，显式重置另设入口；定义播放位置更新顺序并校验合法范围。
- **验收**：首次并发保存无 500；乱序 `false/true` 请求不使完成状态回退；关闭页面与正常保存并发时保持一致；异常播放位置被合理处理。

### R05：快速切换课程时旧响应覆盖新页面

- **触发与影响**：课程 A、B 请求重叠，B 先返回，A 后返回并覆盖页面。地址属于 B，却显示 A；购买操作依赖显示对象，也可能使用错误课程 ID。
- **依据**：[CourseDetailView.vue](../learnhub-web/src/views/CourseDetailView.vue) 中 `loadCourse` 与路由监听。
- **复现结果**：路由课程 ID 为 `2`，最终显示课程 ID 为 `1`。
- **建议**：响应绑定发起时的路由及请求序号，或取消过期请求；学习页面的加载和保存回调也应检查相同边界。
- **验收**：模拟 A 慢、B 快及反向顺序，最终始终显示当前路由课程；购买目标与路由一致；过期错误不得覆盖当前页面。

### R06：下架、已购访问和待支付订单规则不一致

- **触发与影响**：课程下架后，详情、课时访问和进度接口要求课程仍为 `PUBLISHED`，已购用户也不能继续学习；此前创建的待支付订单却仍可确认付款，形成付款成功但不可学习的状态。
- **依据**：[CourseService.java](../learnhub-server/src/main/java/com/learnhub/course/service/CourseService.java)、[LearningProgressService.java](../learnhub-server/src/main/java/com/learnhub/course/service/LearningProgressService.java)、[CourseOrderService.java](../learnhub-server/src/main/java/com/learnhub/order/CourseOrderService.java)。
- **待明确**：“下架”是仅停止新销售，还是禁止所有访问？若禁止已购访问，应定义通知与退款处理。
- **建议**：分别定义销售状态和学习访问资格，并统一付款时的状态校验。
- **验收**：覆盖下架前已购、下架前待支付、下架后新购与重新上架；各入口遵循同一规则，不出现付款后没有对应权益的状态。

### R07：秒杀失败补偿后的重试与唯一约束冲突

- **触发与影响**：失败订单保留在数据库，仍占用 `(coupon_id, user_id)` 唯一键；再次领取生成新请求、新订单，插入冲突后再次补偿。前端允许失败后重试，但该路径可能持续失败。
- **依据**：[CouponService.java](../learnhub-server/src/main/java/com/learnhub/marketing/coupon/CouponService.java)、[SeckillPersistenceService.java](../learnhub-server/src/main/java/com/learnhub/marketing/coupon/SeckillPersistenceService.java)、[CouponView.vue](../learnhub-web/src/views/CouponView.vue)、[schema.sql](../sql/schema.sql)。
- **建议**：明确失败订单重试状态流转，在保留用户领取唯一性的同时支持合法重试，隔离旧请求消息与新尝试。
- **验收**：首次持久化失败并完成补偿后可再次成功领取；消息重复、延迟旧消息和连续失败不重复扣库存或重复发券。

### R08：逻辑删除用户仍可能通过认证

- **触发与影响**：用户仅设置 `deleted=1`，状态仍为 `ACTIVE` 时，实体缺少逻辑删除映射，认证读取仍可能返回该用户，使登录、刷新及旧访问令牌继续有效。
- **依据**：[User.java](../learnhub-server/src/main/java/com/learnhub/auth/model/User.java)、[schema.sql](../sql/schema.sql) 及认证链路。
- **边界**：这是指定数据状态下的风险，不表示当前存在已删除用户，也不表示已实现账户删除入口。
- **建议与验收**：统一认证查询的逻辑删除语义；删除用户后登录、刷新及已有访问令牌均应被拒绝。

### R09：OSS 公共域名配置绕过签名访问分支

- **触发与影响**：配置 `LEARNHUB_OSS_PUBLIC_BASE_URL` 后，访问地址直接拼接域名和对象键，忽略签名有效期。若对象公开可读，视频地址可被分享绕过平台权限；若对象私有，直链可能访问失败。接口仍给出过期时间，与实际地址语义不一致。
- **依据**：[AliyunOssObjectStorageService.java](../learnhub-server/src/main/java/com/learnhub/storage/AliyunOssObjectStorageService.java)、[MediaAssetService.java](../learnhub-server/src/main/java/com/learnhub/storage/MediaAssetService.java)。
- **边界**：公共域名配置本身不会将私有 Bucket 自动变成公开；本轮未验证真实 OSS 部署。
- **建议与验收**：封面、头像与课程视频区分访问策略；付费视频使用私有签名地址，过期时间反映真实行为。分别验证公共和私有配置、未授权获取链接及签名过期场景。

### R10：上传完成校验后对象仍可能被覆盖

- **触发与影响**：预签名 PUT 在有效期内可重复使用。首次上传完成并通过元数据检查后，同一地址仍可能覆盖同一对象；资源已为 `READY` 时完成接口直接返回，实际文件可能与已记录大小不一致。上传签名未绑定大小限制。
- **依据**：[AliyunOssObjectStorageService.java](../learnhub-server/src/main/java/com/learnhub/storage/AliyunOssObjectStorageService.java)、[MediaAssetService.java](../learnhub-server/src/main/java/com/learnhub/storage/MediaAssetService.java)。
- **建议**：上传到临时对象，校验后转存为不可被原签名覆盖的正式对象；补充适合存储能力的大小约束、业务授权与配额。
- **验收**：真实 OSS 中验证完成后重用 PUT 地址，正式资源不被替换；超限文件被拒绝，元数据与文件一致。

### R11：点赞收藏缺少目标有效性校验

- **触发与影响**：点赞和收藏写入前缺少充分的目标存在、可见性及删除状态检查，可对无效对象产生 Redis 状态、记录或异步事件；计数更新零行也可能被视为处理成功。
- **依据**：[LikeService.java](../learnhub-server/src/main/java/com/learnhub/interaction/like/LikeService.java)、[FavoriteService.java](../learnhub-server/src/main/java/com/learnhub/interaction/favorite/FavoriteService.java)。
- **建议与验收**：写入前校验目标及允许的类型；不存在、不可见、已删除目标被拒绝且无副作用；合法重复操作保持幂等。

### R12：无内容课程可直接发布

- **触发与影响**：创建时可指定立即发布，发布服务也未检查章节、课时及视频就绪状态，用户可能购买没有可学习内容的课程。
- **依据**：[AdminCourseService.java](../learnhub-server/src/main/java/com/learnhub/course/service/AdminCourseService.java)、[AdminView.vue](../learnhub-web/src/views/AdminView.vue)。
- **待明确**：是否支持预售？支持时应使用明确状态与交付说明。
- **建议与验收**：制定发布条件；空课程、未就绪资源和正常课程分别返回符合规则的结果；发布失败不进入销售和搜索展示。

### R13：秒杀展示库存与预扣库存不同步

- **触发与影响**：请求先在 Redis 预扣库存，数据库扣减等待异步消费；列表读取数据库库存，消息积压时可能显示仍有库存、可立即秒杀，实际已无法领取。
- **依据**：[CouponService.java](../learnhub-server/src/main/java/com/learnhub/marketing/coupon/CouponService.java)、[CouponView.vue](../learnhub-web/src/views/CouponView.vue)。
- **边界**：本问题是展示与交互不一致，不等同于已发生超卖。
- **建议与验收**：使用与预扣一致的可领取库存视图，或明确展示近似库存；在消费延迟、库存耗尽和补偿后验证按钮状态与反馈。

## 4. 产品闭环与管理能力

### G01：优惠券缺少使用闭环

创建接口未提供优惠门槛和金额参数，接口新建券默认门槛、优惠金额为零；现有种子券可能含非零金额，不能概括为所有优惠券均为零元。订单尚未关联优惠券，缺少资格校验、计算优惠、占用、核销、取消释放与退款返还规则。

依据：[CouponRequests.java](../learnhub-server/src/main/java/com/learnhub/marketing/coupon/CouponRequests.java)、[CouponService.java](../learnhub-server/src/main/java/com/learnhub/marketing/coupon/CouponService.java)、[CourseOrderService.java](../learnhub-server/src/main/java/com/learnhub/order/CourseOrderService.java)。

后续验收应覆盖过期券、不满足门槛、重复使用、并发下单、订单取消、支付成功和退款，明确每种状态下金额与券状态。

### G02：支付为演示流程

当前零元确认购买属于既有演示设计，页面也有说明，不能将其当作真实交易验收。正式收费前需完成支付订单、回调验签、金额校验、幂等、超时关闭、退款、对账及权益同步。

依据：[CourseOrderService.java](../learnhub-server/src/main/java/com/learnhub/order/CourseOrderService.java)、[CourseDetailView.vue](../learnhub-web/src/views/CourseDetailView.vue)。

### G03：后台维护能力尚不完整

- 后台已有新增、发布、章节新增、课时增删、视频上传和每页 10 条课程分页。
- 服务端已有课程更新和下架操作，但前端缺少对应入口。
- 仍缺少已有章节与课时的编辑、封面管理、分类与标签管理；分类选项硬编码，创建课程时分类存在性校验也需补齐。

依据：[AdminView.vue](../learnhub-web/src/views/AdminView.vue)、[AdminCourseService.java](../learnhub-server/src/main/java/com/learnhub/course/service/AdminCourseService.java)。

后续验收需覆盖编辑后详情与搜索同步、下架规则、无效分类拒绝，以及修改或删除学习内容后的历史进度处理。

## 5. 建议处理顺序与记录规则

1. R01：收紧优惠券创建权限。
2. R02、R08：统一账号及用户状态的认证边界。
3. R03、R04、R05：修复学习保存与页面请求竞态。
4. R06、R12：确定销售、学习、发布规则后实施校验。
5. R07、R13：完善秒杀重试和库存展示一致性。
6. R09、R10：结合实际存储部署验证媒体访问与上传边界。
7. R11、G01～G03：补齐交互数据约束和产品维护、交易闭环。

后续每项修复应保留问题编号，记录改动提交、验证命令与结果、尚未覆盖的场景。只有取得对应验收证据后才将状态改为已解决；本文件不授权或表示已完成生产环境变更。
