# LearnHub 学习助理：本轮交接与后续上下文工程

## 1. 下一位接手者先读

工作目录：`D:\Project\myproject\LearnHub`。分支：`feature/assistant-rag-agent`。本文编写前 HEAD 为 `de46ea9`，工作区干净；本文提交会位于该提交之后，下轮仍须重新核验 Git。原交接提交 `73a1764` 已核验是当前 HEAD 祖先。未自动合并或推送。

用户当前要求是写交接文档，本文没有实施下一阶段上下文重构。下一轮主任务建议为：**完善上下文工程，同时处理证据不足判断，再进行受控的多轮端到端验收**。不能把“只剩上下文一项”当成准确状态，真实供应商的工具选择、长对话、自动偏好提取和答案质量仍有验收缺口。

首次操作：

```powershell
Set-Location D:\Project\myproject\LearnHub
git status --short
git branch --show-current
git log -15 --oneline
Get-Content AGENT.md
Get-Content docs\ASSISTANT_HANDOFF_20261020.md
```

再读 `docs/LANGCHAIN4J_AGENT_PHASED_PLAN_20261017.md` 的实施更新、`docs/ASSISTANT_CHAT_IMPLEMENTATION_20261018.md` 和本文引用的验收记录。原阶段计划正文包含历史设计快照，**不要从阶段 0 重做已完成的功能，也不要将早期“未实现”或默认关闭描述当成当前状态**。当前代码、配置与新鲜核验优先。

## 2. 操作约束

- 保持 JDK 17、Spring Boot 3.3.5。LangChain4j 已使用普通 Java 模块接入；不要自动升级 Boot 或改用 Starter。
- 读取根目录 `AGENT.md` 和适用的 `AGENTS.md`。每项逻辑变更先验证，再创建中文 Conventional Commit；不自动合并、推送或覆盖他人修改。
- 不擅自重启用户后端。维护测试优先使用无 HTTP 的受控上下文；需要 HTTP 时用独立端口，记录并清理本次进程，不批量杀 Java。
- 密钥只从本地配置读取，不打印、提交或写入报告；不输出临时签名 URL。`.env`、`.local/` 和大媒体源文件不提交。
- MySQL 是课程正文、权限和版本事实源；ES/PG 是可重建投影。Mem0 仅用于学习偏好，不能成为课程事实源。
- 每次向模型重发历史、摘要、检索结果或工具证据前，重新校验所属用户、课程权限、资料版本与偏好状态。继续复用取消、截止时间、并发、成本和 Token 保护。
- 不为测试修改付费课时权限、创建购买记录或把课时改成免费。资料缺失时继续 Fake/HTTP Mock，并注明真实验收缺口。
- 用户曾要求删除中途日志；本轮没有新增日志文件。不要误删本地回退信息、供应商额度台账、持久化数据卷或测试验收报告。

## 3. 已有工程能力与本轮完成事项

### 3.1 LangChain4j 学习助理主链路

最初交接中的“尚未实现 LangChain4j、真实流式、持久化历史、ChatMemory、Embedding/pgvector、Mem0、独立工作区”已不再适用。本线程累计完成阶段 0—8 的工程接入：

| 能力 | 已有结果 | 验收边界 |
| --- | --- | --- |
| LangChain4j | 普通 Java 依赖兼容性验证，手工 Spring Bean 接入 | JDK/Boot 未升级 |
| 独立聊天工作区 | `/assistant`，绑定课程、新建/改名/删除会话、历史、连续追问 | 较早前端回归和浏览器验收含 Mock，不能等同真实模型验收 |
| 会话和运行持久化 | MySQL 消息、运行、幂等请求及租约 | 临时流式内容与最终消息分开 |
| 流式及取消 | 真实流式适配、有界传输、停止生成、截止时间及清理 | 曾完成有限供应商烟测，覆盖范围见预算记录 |
| Agent 与工具 | AI Services 自主只读工具循环、工具状态、参数/范围校验 | 真实供应商组合工具行为仍需扩展验收 |
| 安全证据 | 来源编号、历史依赖信封、权限/版本复核 | 重构上下文时必须保留 |
| 检索 | 授权 ES BM25、本地中文 BGE、PG 投影补偿、RRF | 来源评测不等于答案正确率 |
| 长期偏好 | MySQL 元数据 + Mem0，查看/修改/忘记、跨会话受控召回 | 明确写入已接入；真实供应商自动提取质量未充分验收 |

阶段提交和详细过程见 [实施记录](ASSISTANT_CHAT_IMPLEMENTATION_20261018.md)、[较早系统交付](ASSISTANT_FINAL_ACCEPTANCE_20261019.md)。本轮还修复了整数课程编号工具参数兼容、参数约束保留，以及 JSON 结构化回答模式和流式校验诊断。保留这些约束，不用关闭校验来掩盖模型输出错误。

### 3.2 全课程讲义生成与导入

- 为 24 门课程、51 个有效课时各生成一份教学补充讲义；正文共 51,188 字符。
- 这些是 AI 生成的补充讲义，**不是原教师逐字稿，也没有宣称逐视频审校**。
- 51 份新讲义与原有 6 份文档共计 57 份有效资料、273 个当前分片；原有资料身份、正文和版本保留。
- 51/51 按课时的 ES 来源烟测通过；48/48 有既有可读用户的课时通过授权检索。
- Spring Boot 课程的三个付费课时缺少现成授权读者，只验证索引与拒绝越权；没有补造购买授权。Redis Lua 课时有现成可读用户。

源文件和导入报告：`docs/assistant-materials/` 下的 `catalog-20261020.json`、`lectures-a.json`、`lectures-b.json`、`lectures-c.json`、`courses/`、`import-result-20261020.json`。入口和边界见 [讲义说明](assistant-materials/README.md)。不要重复创建同内容的新来源。

### 3.3 日常课程向量库部署和全量回填

- 已在 Docker Compose `deploy` 项目部署 `deploy-course-vector-pg-1`；PostgreSQL 16 + pgvector 0.8.7，固定镜像 digest。
- 宿主机端口仅 `127.0.0.1:15432`，数据库 `learnhub_vector`，schema `learnhub_rag`，持久卷 `deploy_course-vector-data`。
- 与 Mem0 的 PG 分开；运行账号只授目标投影表 DML、schema USAGE、数据库 CONNECT，无超级用户、建库、建角色或 schema CREATE 权限。
- 使用本地模型 `bge-zh-v15-q-lc122-t480-o32-v1`，512 维，当前使用精确余弦检索，未启用 ANN/HNSW。
- 57 份文档、273 个分片全部回填为 273 条向量。核对的是文档/版本/片段身份精确集合，不能仅靠条数认定正确。
- 重复同步编码为 0；全部文档和片段字段前后快照一致。回填没有调用付费生成或外部 Embedding 服务。
- 修复 Docker Desktop internal 网络没有宿主机端口映射的问题，以及 `.env` 大写 profile 设置缺少显式映射的问题。

维护命令见 [部署说明](../deploy/README.md)，结果见 [向量验收](ASSISTANT_VECTOR_LOCAL_ACCEPTANCE_20261020.md)。不需要重新部署或再做一次全量回填才能开发上下文。

### 3.4 冻结 72 题评测

题集 `docs/assistant-retrieval-local-20261020.json` 已在第一次执行前提交冻结：48 道可回答题、12 道资料不足题、12 道跨课程题；覆盖 24 门课程。每路执行 72 题，分别比较 BM25、语义、BM25 + 语义 + RRF，RRF k=60，单路候选上限 100，返回最多 5 个分片。

| 首轮指标 | BM25 | 语义 | 混合 RRF |
| --- | ---: | ---: | ---: |
| 可回答题预期来源命中@5 | 48/48 | 48/48 | 48/48 |
| 来源 MRR@5 | 0.9375 | 0.9306 | 0.9583 |
| 平均延迟 ms | 36.75 | 34.85 | 53.50 |
| P95 延迟 ms | 47.82 | 47.09 | 83.65 |
| 资料不足题仍返回弱来源 | 12/12 | 12/12 | 12/12 |

三路在资料不足题中各累计 60 个弱来源。21 次真实不可读课时排除检查无越权。只证明来源召回与排序，不能证明回答准确、拒答有效或泛化到新文档；该题集已经看过，后续调优不能将它重新称为未见留出集。

首轮与增强复核原始报告分别为 `docs/assistant-vector-local-first-20261020.json`、`docs/assistant-vector-local-verified-20261020.json`；首次报告保留不覆盖。

### 3.5 Docker 精简和媒体迁移

- Mem0 已合并到 `deploy` 项目，四个组件分别是 API、偏好 PG、本地 Embedding 桥、宿主机回环入口，功能依赖不能随意删除其中一个。
- Kibana 改为 `tools` profile，曾按用户要求停止，约释放 662 MiB；**本文复查时它已重新运行**，不要依据旧记录再次停止它。
- 本地媒体目录为空，按仓库原脚本重新生成 21 门演示短课的 42 个视频和 21 个 PNG 封面。
- 视频总时长 6,868 秒；封面与视频合计 117,921,619 字节。42 个视频全部通过完整解码、去重和画面变化验证。
- 63 个文件已真实上传 OSS，复用现有管理服务绑定原课程/课时；首次与幂等复跑各完成 63 次 HEAD、63 次 HTTP 验证，视频 Range 返回 206。幂等复跑新增上传为 0。
- 数据库中 `8091` 封面/视频引用为 0，演示课程搜索同步待办为 0。原有三门课程及九个视频绑定未变；原课程的其他已有封面没有被批量替换。
- `course-media` 容器、Compose 服务、专用 Nginx 配置及过时媒体目录变量均已删除，没有删除数据库卷。

详细记录及复跑方法见 [OSS 迁移记录](COURSE_MEDIA_OSS_MIGRATION_20261020.md)、`docs/course-media-oss-migration-20261020.json`。旧 `sql/demo-courses.sql` 和本地 8091 启动记录是历史演示流程，不能重跑来覆盖当前绑定。

## 4. 当前本机配置与服务快照

以下仅是本文编写时读取本地配置的非敏感开关，**不是对当前用户后端 JVM 已加载配置的证明**。下一轮只读核对，不打印整个 `.env`：

```dotenv
SPRING_PROFILES_ACTIVE=local,vector
LEARNHUB_AI_MODE=DEEPSEEK
LEARNHUB_ASSISTANT_CHAT_ENABLED=true
LEARNHUB_ASSISTANT_LANGCHAIN_ENABLED=true
LEARNHUB_ASSISTANT_MEMORY_ENABLED=true
LEARNHUB_ASSISTANT_MEMORY_AUTO_EXTRACT_ENABLED=false
LEARNHUB_VECTOR_ENABLED=true
LEARNHUB_VECTOR_HYBRID_ENABLED=true
LEARNHUB_VECTOR_SYNC_ENABLED=true
LEARNHUB_OSS_ENABLED=true
```

注意：向量验收文档写的是执行当时 `hybrid=false`；**用户后来已改为 true，本文已核对**。代码和示例默认关闭不等于本机当前关闭。更新 `.env` 后需用户正常重启后端才会加载，代理没有擅自重启。资料不足题的风险在打开混合检索后仍然存在。

当前 `deploy` 中运行 MySQL、Redis、RabbitMQ、Elasticsearch、Kibana、四个 `memory-*` 和课程 PG，共 10 个容器。课程 PG、Mem0 API/PG 以及基础组件有健康检查者均显示健康；`course-media` 已不存在。Kibana 可选，不是业务依赖；该快照可变化。

Mem0 入口为宿主机回环端口 18280，内部 PG 不作为课程向量库。课程 PG 为 15432，不能用 Mem0 连接地址替代。Mem0 的 `infer=false` 明确偏好写入和课程 RAG 是不同职责，课程正文没有导入 Mem0。

## 5. 上下文工程：当前真实起点

不要描述为“完全没有上下文”。当前主入口是 `learnhub-server/src/main/java/com/learnhub/assistant/chat/model/LangChainChatGenerator.java`：

1. 根据 owner 和持久化运行定位会话；问候语可走不调用模型的分支。
2. 从 MySQL 读取最多 12 条近期消息作为候选。
3. `selectHistory` 只选择成对、已完成且通过当前权限/依赖复核的历史；`MAX_HISTORY_MESSAGES=4`，即最多两轮。
4. `MessageWindowChatMemory.withMaxMessages(12)` 用于本次输入组装；这不表示实际会给模型 12 条历史，也不取代 MySQL 持久化。
5. 恢复历史证据、移除旧回答的引用编号、注入受控偏好，再通过 `AuthorizedCourseRag` 组装本轮授权资料。
6. 当前检索 query 将本轮问题与上一问题简单拼接，长度限制 500；没有完整的指代消解或上下文检索规划。
7. 已有证据数量上限、输入 UTF-8 字节上限、输出/Token 预留、运行限额、截止时间和取消检查。不能说“没有预算保护”，但它还不是各类上下文的动态预算规划。
8. 每次模型/工具及流式输出前后的既有复核由相关 guard、EvidenceBook 和 IO scope 执行，后续重构不能绕开。

关键文件：

- `assistant/chat/model/LangChainChatGenerator.java`：历史筛选、query、提示词、工具循环与流式输出。
- `assistant/chat/application/ChatHistoryService.java`：读取正式历史时的依赖验证。
- `assistant/chat/application/ChatEvidenceCodec.java`、`assistant/qa/EvidenceBook.java`：历史依赖与本轮证据账本。
- `assistant/chat/rag/AuthorizedCourseRag.java`、`assistant/knowledge/KnowledgeRetrievalService.java`：授权 RAG 与三路检索接入。
- `assistant/runtime/AssistantIoScope.java`：截止时间、取消与下游请求约束。
- `assistant/memory/PreferenceMemoryService.java`：长期偏好及同步边界。

以上均相对 `learnhub-server/src/main/java/com/learnhub/`。先沿调用链读代码和专项测试，再确定改造点。

## 6. 待完成事项与建议顺序

以下为后续设计建议，**不是本轮已实现能力**。

### P0：结构化上下文规划与证据不足判断

- 把输入拆为固定规则、当前任务、近期完整对话、可选摘要、已授权证据、偏好、工具观察；按模型窗口预留输出和工具回合预算，再分配各部分空间。
- 先复用现有 guard、RAG、证据 Codec 与预算实现，不引入重复 Agent 框架或新数据库。可抽取上下文规划/渲染职责，但不要先写大量空接口。
- 明确去重身份与版本、排序、截断优先级及丢弃原因。保留用户/助手对、工具调用/结果对的完整性，拒绝直接截断 JSON。
- 处理“它”“刚才那个”“继续讲”等指代；检索改写只能用于查询，不得把改写结果当成事实或放宽课程权限。
- 给资料不足增加独立的证据充分性判断。RRF 排名高不表示绝对相关，不可只用“检索结果非空”决定可回答；阈值/重排方案应先评测再选择。
- 记录不含正文、密钥或个人敏感信息的决策统计，如各部分估算开销、选入/丢弃原因、证据数量、检索降级和充分性结果。

验收至少覆盖：普通追问、指代追问、切换话题、历史很长、工具结果很大、预算不足、同一片段重复召回、资料不足、向量故障回退、撤权及资料换版本。请求保持在现有限额内，权限隔离不能退化。

### P1：可失效的会话摘要与压缩

- 先决定是否确需新增持久化摘要。若需要，可考虑记录覆盖的消息序号、摘要版本、消息摘要哈希、提示版本、资料/偏好依赖及有效性；这是建议字段，当前没有宣称已建摘要表。
- 用户目标、明确自述、未完成任务与助手推断分开，摘要不提升助手文本的事实可信度。保留可追溯来源，不能用一段无依赖纯文本永久替代旧证据。
- 资料版本变化、撤权、偏好修改/忘记后，旧摘要应失效、降级或重建；下次发送前仍须重查。
- 保留近期完整回合，摘要优先覆盖较早稳定信息；失败、取消、重试和并发生成不能误推进摘要覆盖序号。
- 先使用确定性 Fake/HTTP Mock 验证协议与失效逻辑。摘要若调用 LLM，必须计入统一调用数/Token/成本与取消保护，不能作为隐藏的额外调用。

### P2：新评测与真实供应商验收

- 另建在实现前冻结的多轮留出集，不拿已经见过的 72 题重新报告“独立首次成绩”。保留首次失败，优化在开发集进行。
- 分开评估来源命中、上下文覆盖、指代正确率、拒答、引用有效性、越权/过期泄漏、延迟和实际成本；不能把来源 Recall 当回答正确率。
- 验证真实供应商自主工具选择/组合、长对话、结构化流式输出、停止生成，以及有需要时的偏好提取。
- 浏览器 Mock、基础设施集成与真实模型各自报告。媒体 OSS 验收不能充当模型验收。

### 维护事项

- `Mem0HttpClientTest.scopeDeadlineBoundsWholeRequestAndPreservesDeadlineCode` 在一次全量回归中出现过 `MEMORY_UNAVAILABLE` 与预期 deadline 错误码不一致；单项及最终全量复跑通过，没有修改 Mem0 生产逻辑。若再现，调查客户端超时与 scope 截止的竞争，不能反复重跑直到碰巧变绿。
- AI 生成讲义仍需内容审校；目前没有确认与全部视频逐条一致。
- 日常运行环境配置与默认示例不同，下一轮不要擅自将用户打开的混合检索改回 false。

## 7. 付费调用授权边界

此前用户只授权一个有限真实烟测批次：最多 3 次调用、累计最多 12,000 预留 Token、单次最多 512 输出 Token。台账为本机 `.local/langchain-phase3-live-ledger.json`，当前已核对 `calls=3`、`reservedTokens=11592`，**调用次数已用满**。

历史结果为一次失败、一次带历史追问成功（324 实际 Token）、一次首字后取消；失败/取消用量不能当作 0。不要删除、重置台账或换进程绕过次数限制。用户本轮授权的 OSS 媒体上传不等于追加 DeepSeek 调用预算。

可继续本地 BGE、Fake 和 HTTP Mock。需要新增真实模型验收时，先准备可审阅的测试范围与预算，再取得用户明确的新批次授权；不要索取或展示密钥。

## 8. 测试基线与提交索引

最新代码交付基线为 `de46ea9`，根目录 `mvn test`、`mvn verify` 最终均通过：各 580 项、467 实际执行、113 条件跳过，零失败/错误。真实 OSS 迁移另显式执行两轮；回填/导入等维护测试默认跳过，不能把跳过算成真实通过。

前端较早基线为 Node 105/105、类型检查与构建通过，详见阶段 8 记录；本次向量部署及媒体迁移没有改前端，也未重新跑前端全套。本文只做文档整理，不冒充本轮又执行了完整代码回归。测试进程已在代码交付时核对清理，没有保留临时后端。

Maven 路径：`D:\Android\apache-maven-3.9.10\bin\mvn.cmd`。后续后端变更按仓库规则先专项再根 `test` / `verify`。本机真实维护命令见各专项记录，不因接手而重复写入业务库。

| 提交 | 内容 |
| --- | --- |
| `66ba739` | Mem0 合并到 deploy 并复用记忆卷 |
| `6ef0690`、`0bda297` | 整数课程工具参数兼容、保留参数约束与脱敏诊断 |
| `2669396` | 全课程生成讲义与真实导入 |
| `44d07a6` | JSON 模式与流式输出校验诊断 |
| `30f656e` | 72 题检索集首次执行前冻结 |
| `cf2d839`、`0f2b5fb` | 独立课程 pgvector 部署与宿主端口修复 |
| `068e6eb`、`f68cfaf` | `.env` profile 映射与隔离测试 |
| `d69fab0` | 全量向量回填与首轮/增强检索回执 |
| `cb76e1f` | Kibana 按需 profile 与服务用途说明 |
| `d12b05b` | 兼容 OSS 的 PNG 封面生成 |
| `99ba2a1` | 真实 OSS 媒体迁移及访问验收工具 |
| `de46ea9` | 迁移完成后移除本地 Nginx 与交付记录 |

## 9. 参考项目与可直接给下一对话的提示

参考项目位于：

- `D:\Project\Agent\mewcode-java`
- `D:\Project\Agent\SuperBizAgent-release-2026-05-17`

此前分析可用来理解消息/工具循环与上下文组织，但不能当作 LearnHub 已实现证据。本线程对 SuperBizAgent 的代码分析指出：普通聊天主要用内存会话、最多六对历史和字符串拼接；没有看到主路径接入摘要、持久化 checkpointer 或动态预算规划。它另有工具型检索和 Markdown/段落切分。下轮如引用精确实现仍应复核源码；本文没有重新审查这两个外部项目，也不据此认定 LearnHub 应照搬其设计。

可复制的接手提示：

> 请接手 `D:\Project\myproject\LearnHub` 的学习助理上下文工程。先核验 Git 状态、分支和 HEAD，读根 `AGENT.md`、`docs/ASSISTANT_HANDOFF_20261020.md` 与原分阶段计划的实施更新。现有 LangChain4j、流式、MySQL 历史、ChatMemory、只读工具、Mem0、BM25+BGE+RRF 均已接入；57 份资料/273 向量已真实回填，21 封面/42 视频已迁 OSS，course-media 已删除。当前历史输入最多两轮，仍缺动态上下文规划、可失效摘要及充分的证据不足/真实回答质量验收。按交接建议先做调用链审计和测试，再逐步实现，复用权限/版本复核与成本保护，不重做阶段 0，不自动升级 JDK17/Boot3.3.5，不重启用户后端。当前本地 hybrid 已为 true；付费三次烟测额度已用满，先 Fake/HTTP Mock，不重置台账。每阶段验证后提交中文 Conventional Commit，不自动合并。报告具体改变、实际测试和未验证边界。
