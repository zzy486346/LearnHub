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
