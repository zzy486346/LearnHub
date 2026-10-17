# LearnHub RAG 与学习助理 Agent 实施验收（2026-10-17）

> 真实模型续验已完成，最新调用数、首次留出失败项与本地启用方式见 [DeepSeek 续验报告](ASSISTANT_DEEPSEEK_LIVE_ACCEPTANCE_20261017.md)。下文保留凭据到位前的阶段记录，原有“缺 Key / 调用 0”不代表最新状态。

## 交付结论

A～F 的代码闭环已经实现：真实材料、DeepSeek HTTP 薄适配器、带引用课程 RAG、课程页入口、四个只读工具、模型决定工具的受控循环及同步运行保护。保留 Disabled/Fake；未升级 Spring Boot、引入 AI 框架或增加运行依赖，未实施 G / pgvector、MCP、写工具或长期会话。

**真实 DeepSeek、真实模型 Tool Calling、20 条留出生成质量均为 0 次，尚未验收通过。** 显式开启最小烟测后因本地缺少 API Key 跳过；未要求用户在聊天提供密钥。下面的脚本模型与真实服务测试证明程序链路，不代表真实模型质量。

## A～F 实现与输入输出

| 阶段 | 输入 → 输出 | 实现证据 |
| --- | --- | --- |
| A 材料 | MySQL 真实课程归属 + 审校场景 → 6 文档 / 18 块 | [材料基线](ASSISTANT_STAGE_A_20261016.md)；8 条 BM25 开发集，20 条留出规格独立保留 |
| B 模型 | 消息 / 工具 schema / 预算 → 文本或工具请求 / usage / 结束原因 | [官方契约核验](ASSISTANT_DEEPSEEK_20261016.md)；DeepSeekChatModelClient；HTTP Mock |
| C RAG | 登录身份 + courseId + question → 带证据编号的分段回答与站内来源 | CourseQaService、EvidenceBook、GroundedAnswers；无证据拒答，未知引用/畸形输出拒绝 |
| D 页面 | 课程页问题或学习目标 → 回答、来源跳转、取消、执行记录 | CourseAssistantPanel；纯文本渲染，账号/课程/模式变化清旧结果，付费 POST 不自动刷新重放 |
| E Agent | 学习目标 → 模型选工具 → 工具观察 → 再调用模型或结束 | AssistantTools、CourseAgentService；严格 schema、只读白名单、工具编号及重复调用保护 |
| F 保护 | 每次请求 → Redis 许可、预算预留、身份与证据复核、最终释放 | AssistantRunGuard / ExecutionManager / IoScope；超时取消 HTTP、ES、SQL；跨实例取消 |

MySQL 始终为事实源，Elasticsearch 仅为可重建 BM25 投影。每轮发出完整历史前、工具前后、生成后均复核课程权限和材料版本；旧证据失效会废弃整次结果，不能删掉引用继续返回原正文。结构化目录/进度也提供当前快照复核器。引用校验验证本次来源、版本、权限、结构和建议所需证据类型；**它不等于自动证明每句话在语义上得到材料支持**，支持度仍需独立质量评测。

四个工具为 `search_courses`、`get_course_outline`、`search_course_materials`、`get_my_learning_progress`。身份只来自登录上下文；模型参数不接收 userId、角色、SQL、Shell 或任意 URL。目录不包含媒体地址，进度只取本人且过滤失效/不可读课时。SUGGESTION 同时要求 PROGRESS 与 OUTLINE 引用。

## 使用方法

1. 由用户正常启动现有中间件和前后端。代理没有留下演示后端，也没有重启用户已有后端。知识同步配置及真实材料详见阶段 A 文档。
2. `.env` 当前保持 `LEARNHUB_AI_MODE=DISABLED`。设为 `FAKE` 可以演示接口、取消与明确模拟标识；Fake 固定资料不足，不会自动演出成功回答或工具选择。
3. 有本地凭据后，将 API Key 写入被忽略的 `.env` 的 `LEARNHUB_DEEPSEEK_API_KEY`（或进程 `DEEPSEEK_API_KEY`），设置 `LEARNHUB_AI_MODE=DEEPSEEK`，由用户正常启动后端。默认 `deepseek-flash`；供应商地址固定为官方 API。
4. 登录后打开任一有可读材料的课程页，选择“课程问答”或“学习建议”。Java 示例课程为 `/courses/12002`；可问“结合我的进度、目录和材料，建议下一步学习什么？”来源链接跳转课程或课时。
5. 接口为 `POST /api/assistant/course-qa`（courseId/question）、`POST /api/assistant/agent`（courseId/goal），以及 `DELETE /api/assistant/runs/{requestId}`。生成请求可附 UUID `X-Assistant-Request-Id`；无需传用户身份字段。

默认保护：45 秒总时限、4 次模型调用、3 轮工具决策、4 次工具、每用户 1 个运行、全局 4 并发、每用户每分钟 6 次、每日模型调用 20 次 / 预留 120000（用户与全局同时约束）。单次输出取 1200 与 AI 配置 1024 的较小值，输入 16000 字节，本次预留 24000。全部可在 `.env.example` 对应配置中收紧。

输入按完整历史 UTF-8 字节加余量做保守估算，**不是精确 tokenizer**。已知 usage 计入实际累计上限，失败/取消仍保留预算预留，未知 usage 不归零。无自动付费重试。取消连接不保证供应商停止推理或免费。

## 验证证据

| 检查 | 实际结果 |
| --- | --- |
| 材料准备 / 映射 Node | 10 执行、0 跳过、0 失败 |
| 前端全部 Node 回归 | 83 执行、0 跳过、0 失败 |
| 前端 `npm run build` | vue-tsc 与 Vite 均通过；现有主包体积告警仍在 |
| 最终 `mvn test` | 299 总数，254 执行、45 跳过、0 失败/错误；启用真实 Redis、SQL 取消、Agent 场景 |
| 最终 `mvn verify` | 299 总数，248 执行、51 跳过、0 失败/错误；打包成功，opt-in 默认关闭 |
| 隔离知识基础设施集成 | 7 执行、0 跳过、0 失败；独立 MySQL + 现有 ES，临时容器清理成功 |
| Agent 专项 / 工具专项 / MVC | 8 / 8 / 5 实际执行并通过 |
| 真实中间件 Agent 场景 | 2 执行、0 跳过；5 次只读工具、6 次脚本模型响应 |
| 最小 DeepSeek 烟测 | 显式 opt-in，实际调用 0；缺 Key 跳过 1 项，不算通过 |
| 真实模型 Tool Calling / 留出生成评测 | 0 / 0；20 条留出未执行 |

场景中的真实本人进度为空，已与数据库对照；输出说明尚无记录，不伪造学习成果。非空真实进度与真实模型学习建议尚未验收。[场景、计数与复现命令](ASSISTANT_SCENARIO_VERIFICATION_20261017.md)。不同命令是重复回归，不能相加当作独立场景数；默认关闭的中间件测试不算实际执行。

本地日志（被 Git 忽略）：`.local/assistant-final-mvn-test.log`、`assistant-final-mvn-verify.log`、`assistant-final-knowledge.log`、`assistant-final-mvc-smoke.log`、`assistant-final-ui-tests.log`、`assistant-final-ui-build.log`。仓库中的测试、断言与此报告提供可复现证据，日志不提交。

复现后端全量验收：

```powershell
$env:LEARNHUB_ASSISTANT_REDIS_IT='true'
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' '-Dlearnhub.assistant.io.it.enabled=true' '-Dlearnhub.assistant.scenario.it=true' test
Remove-Item Env:LEARNHUB_ASSISTANT_REDIS_IT
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' verify
./scripts/assistant/verify-knowledge.ps1
```

真实单次烟测命令见场景文档：最多 1 次、128 输出、10 秒、无重试。它仅验证基本 JSON / usage 契约；后续真实 Tool Calling、RAG 支持度和留出质量也须逐项设置独立有限次数与累计 Token 上限后执行，不得将单次烟测扩称完整验收。

## 提交记录与边界

功能分支 `feature/assistant-rag-agent`，从本地 `master` 创建，未合并或推送。原分支及原有改动已保护；远端更新因本地代理不可达未验证，不能声称基于最新远端。

- `82102b2` feat: 建立学习助理材料与评测基线
- `146707a` feat: 接入DeepSeek结构化模型客户端
- `edb2367` test: 完成学习助理材料真实导入验收
- `4ffae1b` feat: 增加学习助理运行保护
- `e0af695` feat: 增加助理超时取消与身份复核
- `7891f73` feat: 实现授权课程RAG与引用校验
- `a2c5059` fix: 完善跨实例取消与实际用量保护
- `2a972c7` fix: 保留模型失败响应的已知用量
- `25c4a3a` docs: 保留RAG与Agent阶段实施方案
- `f5856aa` feat: 增加课程问答与来源跳转入口
- `2d10889` feat: 增加学习助理只读工具
- `aec6df5` feat: 增加学习建议模式与工具执行记录
- `30927c9` feat: 实现受控学习助理工具循环
- `93b85da` test: 验证Agent接口绑定登录身份
- `22dc9c1` test: 验证真实只读工具与学习建议场景


独立安全审查未发现阻断问题。现有可用性限制：课程搜索遇 ES 陈旧下架候选会中止整次请求；结构化证据失效可能使用原课程权限错误码；失败工具仍出现在执行步骤中。均停止/限制请求，不会继续发送失效正文。未为本任务修改业务学习进度或安装新中间件。最终核验本次 Surefire 测试进程 92428 / 98832 / 103740 / 104432 均已退出；阶段 A 临时后端 PID 86092 已清理，18081 无监听；隔离 MySQL 容器已删除，测试 Redis 使用独有随机前缀并清理。未按进程名批量停止 Java，未修改用户已有后台服务。

## 简历表述

已能用真实代码支撑“基于 Elasticsearch BM25 的授权知识检索，设计并实现带来源校验的课程 RAG 链路及四工具受控 Agent 循环，完成 HTTP Mock 和真实中间件集成验证”。必须同时注明真实 DeepSeek 与留出质量待验收。

当前不能写“已完成真实模型 RAG 质量验收”“真实模型自主调用工具生成个性化学习建议已验证”或任何向量/混合检索、MCP 能力。待凭据到位，完成受限真实生成、Tool Calling、非空进度及独立留出支持度验收，再升级为已验证的完整 RAG / Agent 简历描述。
