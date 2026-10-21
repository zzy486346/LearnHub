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
