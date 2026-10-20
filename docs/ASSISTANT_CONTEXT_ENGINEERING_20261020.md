# 学习助理上下文工程实施记录

## 起点与约束

2026-10-20 接手核验：`feature/assistant-rag-agent@3e795db`，工作区干净。已读取根 `AGENT.md`、当日交接及原计划实施更新。旧记忆中的阶段 0 起点已经过时，以当前代码为准。只读核对本地 `hybrid=true`、付费台账 `calls=3/reservedTokens=11592`；不增加真实调用、不改台账、不重启用户后端、不升级依赖、不自动合并或推送。

## 调用链审计

- `LangChainChatGenerator.prepare`：校验 owner/conversation、guard/IO scope，MySQL 取 12 条候选，选择最多两轮完整已完成对话，恢复依赖后进入授权 RAG。
- `ChatHistoryService.verify` 调用 `ChatEvidenceCodec.restoreInto`，重新验证材料、课程/目录/进度快照和偏好；失效回答连同对应用户问题不进入上下文。
- `AuthorizedCourseRag` 使用当前身份/课程检索，`EvidenceBook` 合并与去重，MySQL 复核权限、正文和版本；RRF 排名不是绝对相关度。
- Agent 的 `beforeRound` 每次重新渲染当前证据和偏好，按完整输入加工具 schema 和固定开销预留预算；`reserveModel` 以 UTF-8 字节作为保守 Token 上界，输出另预留。每次调用累计扣减，未知用量不退款。
- 工具执行、模型重发、流式段落与最终结果继续执行 `guard.check/book.check/io.remaining`。不得因裁剪或摘要跳过这些检查。

## 改造计划与验收门槛

1. 先跑现有 HTTP Mock/依赖复核测试，冻结新的上下文验收规范；保留失败证据。这里的规范不是未见真实模型测分结果。
2. 提取一个确定性上下文规划器：保留固定规则和当前任务，按输入字节与剩余运行预留分配历史空间；整轮选入/丢弃，完整 JSON，保留工具调用/结果配对。仅指代追问带入已复核上一问题，切换主题不拼接。逐次模型调用再规划，统计只含数量、字节与原因。
3. 压缩较早回合时先用确定性、每次重建的会话摘要，不新增数据库/模型调用。摘要只记录有来源序号的用户原话片段，区分用户自述与事实；依赖失效整轮移除，近期完整回合优先。明确其有限候选窗口、无语义总结能力。
4. 证据不足单独处理和测试：空证据可确定拒答，非空弱证据需要支持性评估；不得凭 RRF 分数设未经评测的通用阈值。协议验证与真实答案质量分别报告。
5. 每项逻辑变更运行专项及根 `mvn test` / `mvn verify` 后中文 Conventional Commit。记录实际执行/跳过数与剩余验收边界；付费额度不足不阻碍本地协议与安全验收，但不能宣称真实模型质量通过。

## 验证记录

- 审计基线：6 个测试类合计 55 项，全部执行、零失败/错误/跳过。命令为根 Maven `-pl learnhub-server -am test -Dtest=LangChainChatGeneratorTest,LangChainToolChatGeneratorTest,LangChainChatGeneratorContextTest,AuthorizedCourseRagTest,ChatEvidenceCodecTest,EvidenceBookTest -Dsurefire.failIfNoSpecifiedTests=false`。
- 首次命令因 PowerShell 未引用含点参数而失败，已修正；沙箱内 Java 测试启动受限并停滞，终止该次命令后使用授权执行完成基线。没有重跑业务库导入或付费请求。
- `assistant-context-holdout-20261020.json` 冻结 16 个自造 fixture 的多轮规范，状态 `NOT_RUN`。它用于协议/回归覆盖与后续供应商验收，不是回答质量成绩；实现者已经看过规范，不再声称未见留出集。

## 动态上下文第一步

- 新增 `ChatContextPlanner`，统一计算消息、JSON 转义、工具 schema 和 2048 字节协议开销；容量取本地输入上限与运行剩余预留减输出的较小值。这延续现有 `reserveModel(inputBytes, outputTokens)` 的字节作为 Token 上界策略，保守而非供应商精确分词。
- 历史候选由 12 条扩大至 48 条，先按 owner/course/已完成配对与依赖复核，再以剩余空间的一半选入完整回合，剩余空间留给工具观察。历史正文和依赖一起计费，证据仍最多 20 条；同一材料仍由账本去重。预算不足不拆 JSON，也不拆工具链。
- 本轮材料优先通过实际 LangChain4j Augmentor 获取、验证和登记，随后恢复准入历史，统一按账本渲染当前输入。不是重复检索；RAG 内容验证仍执行，模型输入格式集中在 `currentMessage`，避免把最初的 JSON 快照当成工具后最新证据。
- 新增确定性 `ConversationQueryPlanner`。独立问题不拼接上一问；指代问题从已经通过依赖复核的候选中寻找最近明确问题。查询只用于召回，可能使用因输入预算未选入完整对话的问题，不把查询改写当事实；它不具备完整语义指代消解能力。
- Agent 每次发送前再次按当前已预留总数规划，先移除较早普通历史对；固定规则、当前问题、工具调用与结果、完整证据不做字符串截断。必要输入仍超限时在预留/HTTP 前失败。日志只记录模式、数量、预算和丢弃数。
- 专项 67 项全部实际通过，零失败/错误/跳过（首次新增测试编译发现两个 `Long` 参数错误，已修正后执行）。根 `mvn test` / `mvn verify` 分别通过：592 项，479 实际执行、113 条件跳过，零失败/错误；Mem0 deadline 测试本轮两次完整回归均通过。没有启用真实维护/付费验收测试。
