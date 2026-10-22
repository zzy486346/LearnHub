# 学习助理上下文重构实施记录

对应[重构方案](ASSISTANT_CONTEXT_REDESIGN_PLAN_20261021.md)。方案正文保持设计快照，本记录区分实现与验证状态。

## 接手核验

- 2026-10-21：`feature/assistant-context-redesign-plan@b641c204ad6e3103121fcd54e2f91324f87ac9d5`，工作区干净。从该提交建立 `feature/assistant-context-redesign` 实施分支，保护已有方案及后续修复。
- 已读取根 `AGENT.md`、方案、当日检索门槛记录、上下文工程记录及原分阶段计划实施更新。旧交接的两轮历史限制不再是当前实现。
- 只读核验付费台账 `calls=3/reservedTokens=11592`。本次仅 Fake/HTTP Mock，不重置台账、不重启用户后端、不改业务数据库、不升级 JDK 17 / Boot 3.3.5，不合并或推送。

## 阶段 A：审计与冻结

当前调用链：`LangChainChatGenerator.prepare` → 正式历史配对/`ChatHistoryService.verify` → 偏好与授权 RAG → `EvidenceBook` → 历史准入/摘录 → 普通流式或 `ToolStreamingSession`。Agent 每轮重建当前证据，规划后 `reserveModel`，工具/流式/最终回答继续复核。`ChatEvidenceCodec.restoreInto` 复核完整依赖；`ChatPersistenceService.complete` 在事务中检查归属、运行状态和租约后写正式回答。

审计发现：`ChatContextPlanner.capacity` 同时接受输入字节、累计预留 Token、输出 Token；`bytes` 是消息字符串 JSON、工具 schema 加 2048 字节余量的规划估算，并非 SDK 实际 HTTP 正文。历史固定半额及 `user-extract-v1` 是现有行为，阶段 A 保留。SDK 工具链不可被消息数量或字节裁剪拆开。

改造顺序（先冻结再实现）：

1. 冻结新 `context-redesign` 开发回归场景，保留旧 16 题及其 `NOT_RUN`。补完整工具批次/当前任务回归。
2. 拆出纯输入测量与完整消息组，成本容量计算归运行保护；维持保守预留，不用实际 usage 退款。
3. 在受控传输发出 HTTP 前测量 SDK 完整序列化正文；usage 锚点只对可证明相同的请求有效，结构或范围变化重新估算。未证明相同前缀时不使用追加估计。
4. 验证输入硬限、累计成本拒绝、依赖变化、取消和工具流式回归，依次执行根 `mvn test`、`mvn verify` 后提交。

基线命令：

```powershell
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' -pl learnhub-server -am '-Dtest=ChatContextPlannerTest,LangChainChatGeneratorTest,LangChainToolChatGeneratorTest,LangChainChatGeneratorContextTest,ToolStreamingSessionTest,ChatEvidenceCodecTest,EvidenceBookTest,AssistantRunGuardTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

基线结果：88 项全部实际执行，失败/错误/跳过均为 0。首次沙箱执行停在 Mockito/JVM 启动，已终止该命令；正常本机权限下串行执行退出 0。没有并行运行 Maven。

冻结增量：新增工具批次顺序/结构变化两项回归后，同一专项命令 90 项全部实际通过。新场景文件 `assistant-context-redesign-20261021.json` 包含 C01—C14，明确标为 `REGRESSION_DEV_SEEN/NOT_RUN`，实际批次结果另存；JSON 解析及 `git diff --check` 通过。本次冻结提交不改变生产逻辑，根全量回归在阶段 A 生产实现后执行。

阶段 B—F 尚未实施；任务状态、语义摘要、持久检查点、工具结果回读及歧义澄清不属于上述基线能力。冻结场景是验收规范，不能据此报告批次通过或真实回答质量。

## 阶段 A：实现与验证

- `ChatInputMeter` 拆出规划估算与实际 SDK 正文测量：规划估算沿用旧算法和 2048 字节协议余量；同步/SSE 的 `HttpClient` SPI 在任何网络发送前测量完整序列化 UTF-8 正文。若正文超过本地硬限或已预留规划量，立即拒绝，已扣成本不退款，也不自动追加预留或重试。
- `AssistantRunGuard.remainingInputReservation` 负责剩余成本容量；规划器只接收外部容量，不再接受运行累计成本参数。实际扣账仍通过原 `reserveModel/recordUsage`、每日额度和 deadline/取消保护。上下文变小、usage 校准均不减少累计预留。
- 新增完整消息组解析：普通历史对成组，当前工具多调用/结果按 ID 与工具名配对，拒绝孤立、重复、错名和缺失结果。阶段 A 仍只淘汰旧普通历史组，工具组保留；固定半额和 `user-extract-v1` 未替换。普通及工具路径均输出计数和枚举选择原因，不输出问题/摘要正文。
- usage 锚点只存在本 run 的 meter 内，保存范围/完整请求 hash、消息数与实际 input usage。只有**完整请求完全相同**才采用该占用估计；system/schema/证据/偏好/摘要/顺序/模型或消息边界变化均重新估计。没有实现猜测性的追加前缀算法，也不跨 run 或用户共享锚点；规划阶段不能证明与最终 SDK 请求相同，因此继续保守估算。该限制是有意保留的，不宣称已优化压缩选择。
- 自动偏好提取使用独立 meter/plan 和既有 guard，同样校验实际正文和窗口，不能消费回答工具循环的 plan。摘要功能尚未接入，不会新增隐藏调用。
- 新增显式 `model-window-tokens` 与 `context-safety-margin-tokens`；未配置窗口（0）允许关闭模型/Fake 场景启动，但需要生成时在 claim/预留/HTTP 前拒绝。配置后始终检查 `estimatedInput + outputReservation + safetyMargin <= modelWindow`，不从模型名推断。

### 模型窗口配置依据

2026-10-21 阅读 [DeepSeek 官方 Models API 文档](https://api-docs.deepseek.com/api/list-models/)：示例分别明确 `deepseek-flash`、`deepseek-v4-pro` 的 `context_window=1048576`，并定义该值包含输入与输出。示例及本地被忽略 `.env` 显式配置 `1048576`，安全余量 `256`；不增加原输入字节、运行 Token、调用次数或日额度。官方文档核验不是已调用当前账号 `/models` 的证据。切换模型或供应商后须重新核验并显式更新。

没有重启用户后端；当前已运行 JVM 是否加载新配置不属于本次证据。示例填写已核验值，应用属性的缺省值仍为 0，避免未显式配置时静默猜测。

### 测试证据与边界

1. 先运行新增 meter 行为测试，因缺少类而编译失败（预期的实现前红灯）；实现后第一次专项 116/116 通过。新增传输测试第一次因 `AssistantRunStore` 包可见性编译失败，已将测试移入 runtime 包修复，未改变生产可见性以迁就测试。
2. 包含窗口、完整组、普通/工具流式、取消、证据/偏好失效、guard 的扩展专项 123/123 通过，失败/错误/跳过 0。最后补累计/日预留不受 usage 锚点影响的断言并完善提取测试后，相关三类专项 28/28 通过。
3. 根 `mvn test`：636 项，522 实际执行、114 条件跳过，失败/错误 0。根 `mvn verify` 同样通过；补齐普通路径计数/原因日志后，最终根 `mvn verify` 仍为 636 项、522 实际执行、114 条件跳过、失败/错误 0，退出 0。
4. 同步及 SSE 真实本机随机端口 Mock 接收计数均为 0，覆盖实际正文超硬限/超预留的四种组合；真实 guard 累计保留 4 次调用、12,050 预留 Token，usage 未知不退款。独立 accepted-body Mock 证明测量字节与服务端接收字节相同。相同请求 usage 锚点两轮测试累计预留 11,024，实际 usage 合计 16，日预留仍扣两次各 5,512；这些是 fixture 数值，不是供应商成本成绩。
5. 窗口未配置时工具生成路径零预留/零 HTTP；偏好提取窗口超限在 HTTP 前拒绝且保留已有预留。模型/摘要/证据/偏好内容变化均不能复用旧锚点。
6. 独立代码审查最终未发现阻断问题；`git diff --check` 通过。没有可用的 Java LSP/ast-grep，未把 Maven 编译冒充独立静态分析。未改前端，未运行前端构建。

专项命令：

```powershell
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' -pl learnhub-server -am '-Dtest=ChatRequestPreflightTest,ChatInputMeterTest,ChatMessageGroupsTest,ChatContextPlannerTest,LangChainChatGeneratorTest,LangChainToolChatGeneratorTest,LangChainChatGeneratorContextTest,ToolStreamingSessionTest,ChatEvidenceCodecTest,EvidenceBookTest,AssistantRunGuardTest,PreferenceExtractionServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' test
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' verify
```

原 16 题、真实质量报告和付费台账未覆盖。新 C01—C14 整批执行器尚未实现，不能用这些组件回归为整批或真实模型质量打分；C09/C10 持久检查点隔离数据库证据属于阶段 C，当前没有新表/游标。弱证据与错误合法引用负向探针保留，结构测试通过不等于事实支持通过。

阶段 A 机器可读证据见 [报告](assistant-context-redesign-a-20261021.json)。最终只读核验台账仍 `calls=3/reservedTokens=11592`，Maven/Surefire JVM 已退出，无本次遗留测试后端；没有停止用户服务。

## 阶段 B：运行内任务状态与语义压缩（2026-10-22）

新路径默认关闭。启用后按输入字节、显式模型窗口和剩余成本三者的最小容量判断压力；保留近期完整回合，连续较早前缀最多摘要一次，不再采用固定半额或用户摘录。`taskState` 与 `semanticContext` 分别渲染在不可信用户数据中，当前原文和本轮证据仍独立保留。工具循环重建请求时继续带入任务状态，流式首段后不新增摘要调用。

摘要使用同一受控 HTTP、IO scope、运行 guard 和 usage 持久化；独立 meter 测量完整同步请求，SDK 重试为 0。输出限定 4096 UTF-8 字节，七类任务字段及摘要条目均携带输入存在的消息序号和依赖身份；助手来源若没有直接用户来源必须标记 `inferred=true`。拒绝额外/重复字段、尾随 JSON、假序号/依赖、外链、超长数组/字符串及非法 Unicode。结构和身份校验不能证明语义忠实，保留“合法结构但错误掌握结论”负向探针。

压缩前先估算后续回答的保守上界，含摘要二次 JSON 转义、依赖信封、近期两组和工具观察余量。只有摘要及后续回答均能装入预算才发送；每日 Redis Lua 原子预扣两次调用及两份 Token，上限不足则零摘要 HTTP 并保留普通回答机会。下一次回答使用已预扣份额，不二次扣账；未使用份额、取消、未知 usage 均不退款。实际调用数与预扣日调用数可能不同，不能把预扣称为真实消费。UTC 跨日也不重复扣这份预留。旧四参数 Lua 调用保持兼容，不影响未重启的旧 JVM。

摘要至少保留 1 秒后续回答时间，不足 500 毫秒摘要窗口则不调用。非法输出或供应商软失败本 run 不重试，记录 `SUMMARY_FAILED`，按完整近期组降级；取消、超时、资料变化、usage 落库失败继续中止。已调用前缀的依赖并入最终复核，非法摘要正文不会进入回答请求。

本阶段只有单 run 内状态：没有检查点表、跨 run 摘要恢复或发布游标；仍最多读取原有 48 条候选。持久化、分页恢复及失效重建属于阶段 C。四类工具投影/回读、任务主体歧义澄清、C01—C14 整批执行器仍未实现。旧 16 题和付费台账未覆盖。

### 验证过程

- 初始组件及关闭新开关路径 58 项、启用路径 63 项、预算边界扩展 89 项均通过。较大专项首次失败是新 fixture 的建议类回答仅有进度缺少目录，修正 fixture 为同一工具批次包含进度及目录，保留生产引用校验后 155 项通过。
- 隔离 `redis:7.4-alpine` 随机本机端口测试 5/5 通过，验证真实 Lua 两调用原子预留、拒绝无扣账及旧四参数兼容。仅使用缓存镜像，无卷挂载，按本次标签检查后停止并自动删除容器；不触碰用户 Redis 或付费台账。
- 根全量首次失败为已有 `Mem0HttpClientTest` 截止时间竞争：JDK 可比绝对 scope 截止时间提前数毫秒报告传输超时，误分类为 `MEMORY_UNAVAILABLE`。保留首次日志 `.local/context-redesign-b-test-first-failure.log`。最小修复记录请求是否受 scope 收紧，优先保留显式取消，再对 scope 限制的超时信号保留 `ASSISTANT_DEADLINE_EXCEEDED`；供应商自身超时仍报不可用。新增合成异常的确定性回归，不放宽超时、不增加重试。
- 最终相关专项 167/167 通过，失败/错误/跳过 0，包含 Mem0、摘要调用、严格协议、预留、完整工具批次、普通/工具流式、输入预检、证据和偏好失效。

根 `mvn test` 和 `mvn verify` 最终均退出 0：670 项、554 实际执行、116 条件跳过、失败/错误 0。机器可读证据见 [阶段 B 报告](assistant-context-redesign-b-20261022.json)。`git diff --check` 通过；未改前端，未运行前端构建；没有可用的独立 Java 静态分析工具，不把编译当作独立静态分析。只读台账仍为 `calls=3/reservedTokens=11592`，无真实生成调用、业务迁移或后端重启。
