# 独立学习助理：本地交付与验收

日期：2026-10-19。工作目录 `D:\Project\myproject\LearnHub`，分支 `feature/assistant-rag-agent`。已再次确认交接提交 `73a1764` 是当前分支祖先；未合并、未推送，JDK 17 / Spring Boot 3.3.5 不变。

## 实现结果与提交

| 阶段 | 当前结果 | 主要提交 |
| --- | --- | --- |
| 0 | 普通 Java 依赖兼容性、流取消实验 | `5042eb9`、`458540e` |
| 1 | MySQL 会话/消息/运行与幂等租约 | `9d07854` |
| 2 | `/assistant` 独立工作区、历史、Fake SSE、停止 | `e06c504` |
| 3 | 生产 LangChain4j 流式、ChatMemory、逐次预算 | `4ceaa48` |
| 4 | LangChain4j 切分与授权 Retriever/Augmentor | `f0cf845` |
| 5 | 本地 BGE、PG 独立配置与投影补偿、受控 RRF | `f460ed2`、`e14f1f5` |
| 6 | AI Services 自主只读工具循环、执行状态、安全逐段输出 | `bcbc346` |
| 7 | Mem0 明确写入/管理/跨会话召回、历史依赖、受控提取 | `28f2543`、`32da783` |
| 部署 | 独立本地记忆服务镜像、Compose、隔离烟测 | `c0525a2` |
| 8 | 新问题首次冻结、检索与系统回归、本交付记录 | 冻结集 `e913937`；首次分数见下文 |

工程功能已实现；真实供应商的自主工具选择、偏好提取与生成答案质量仍有验收缺口。Mock、真实基础设施和供应商验收分开统计，不把检索来源命中率称为答案正确率。阶段过程和较早测试结果见 [实施记录](ASSISTANT_CHAT_IMPLEMENTATION_20261018.md)。

## 使用方法

1. 既有业务库按当前版本应用相关迁移：`V20261015__knowledge_documents.sql`、`20261018_assistant_chat.sql`、`V20261019__knowledge_vector_projection.sql`、`V20261019__assistant_memory.sql`、`V20261019__assistant_preference_extraction.sql`。新建空库才使用完整 `sql/schema.sql`，本轮未重导入用户业务库。
2. 本地配置启用 `LEARNHUB_ASSISTANT_CHAT_ENABLED=true`。无供应商调用的页面演示用 `LEARNHUB_AI_MODE=FAKE`；真实 LangChain4j 路径另设 `LEARNHUB_AI_MODE=DEEPSEEK`、`LEARNHUB_ASSISTANT_LANGCHAIN_ENABLED=true`，密钥仅由本地 `LEARNHUB_DEEPSEEK_API_KEY` 注入。保留既有速率、并发、运行和每日 Token 限额。
3. 按项目既有方式正常启动后端和前端，登录 `/assistant`。选择有权学习且有资料的课程，提问后继续追问；支持刷新历史、重命名/删除会话、停止生成、来源跳转和工具执行状态。可试“结合我的学习进度，建议下一步学什么”。真实课程材料与本人进度是否充足会影响回答。
4. 长期偏好按 [Mem0 本地启动说明](../services/mem0/README.md) 构建/启动独立服务，配置本地服务凭据和 `LEARNHUB_ASSISTANT_MEMORY_ENABLED=true`，使用页面标题内“学习偏好”管理；明确写入无需额外 LLM。`LEARNHUB_ASSISTANT_MEMORY_AUTO_EXTRACT_ENABLED` 默认 false，开启后会从当前明确自述额外提取一次，最多128输出Token，仍占统一额度。
5. 向量投影按 [向量安装记录](ASSISTANT_VECTOR_INSTALLATION_20261019.md) 使用独立 `vector` profile。混合检索默认关闭：当前两个数据集均未证明收益，不为演示自动开启。

本轮没有修改 `.env` 的真实配置或重启用户后端。所有验收用临时后端/Mock/容器均已清理；部署文件供正常本地启动使用，本轮不保留长期服务。

## 最终实际测试

| 范围 | 执行结果 | 边界 |
| --- | --- | --- |
| 根 `mvn test` / `mvn verify` | 各560项，失败/错误0，跳过110；各实际执行450项 | 基础设施与供应商默认关闭的测试不算通过 |
| 隔离会话/记忆 MySQL 批次 | 47项实际通过，零跳过 | 真实数据库37项，历史/会话单测10项 |
| Mem0 SDK / Linux BGE / PG / Java客户端 | 33条脚本断言 + 1项Java测试实际通过 | 固定SDK2.2.1，私网受控数据，真实供应商0次 |
| Mem0 Python 无网络回归 | 9项通过 | 包括提交回滚、重启补偿、删除故障、严格协议 |
| 向量/同步/首次留出检索专项 | 15项实际通过，零跳过 | 本地模型3、PG4、同步/开发集/留出集8；真实MySQL/ES/PG |
| 偏好提取 HTTP Mock | 17项实际通过 | 真正的普通LangChain4j客户端；无付费调用 |
| 前端全部 Node 回归 | 105/105通过，零跳过 | 全量首次发现3项旧测试加载器未处理新增Vue子组件，修复加载器并补账户切换关闭偏好断言后全过 |
| 前端类型检查与构建 | `vue-tsc -b` / Vite通过 | 保留既有大chunk提示，无构建失败 |
| Edge 偏好管理 Mock | 12项通过；390/1440无横向溢出 | CRUD、确认/取消、待同步、冲突/故障反馈、滚动与Escape |
| Edge 聊天全流程 Mock | 5组行为 + 3视口检查通过，控制台错误0 | 快速切换、工具状态、发送/刷新历史、停止清稿、来源XSS转义；375/768/1440 |
| 实际交付 Compose | 初始化、DML账号、只读文件系统、状态卷、本地编码/召回通过 | 首次发现桥临时目录noexec，改为私有exec tmpfs后通过；随机项目清理已确认 |

可复现命令（仓库根）：

```powershell
./scripts/assistant/verify-chat.ps1 -IncludeMemory
./scripts/assistant/verify-memory.ps1 -IncludeJavaClient
./scripts/assistant/verify-vector.ps1
./scripts/assistant/build-memory-images.ps1
./scripts/assistant/verify-memory-compose.ps1
```

三个基础设施脚本只使用随机自有资源，finally 核验归属后清理。ES 使用随机 `knowledge-it-` 前缀索引，未改共享业务索引。Compose 的 Mem0/PG/BGE 仅在 internal 网络，只有固定目标回环入口双网接入；不能称所有容器都没有外网路由。

## 首次冻结30条检索题

先提交 [新问题集](assistant-retrieval-holdout-20261019.json) 再首次执行，没有按结果改问题、改阈值或调提示。真实检索使用隔离 MySQL/ES/PG 与 BGE；权限服务为Mock。共享既有6份受控材料，因此是未见问题评测，不是未见文档，也不是生成答案质量评测。原始首次成绩保存在 [首次结果](assistant-retrieval-holdout-first-20261019.json)，含实际fixture SHA256。

| 指标 | BM25 | HYBRID |
| --- | --- | --- |
| 新问题数 | 30 | 30 |
| 可回答题来源 Recall@5 | 18/18 | 18/18 |
| 6条资料不足题返回弱来源总数 | 12 | 12 |
| 3条跨课程题返回禁止课程来源 | 0 | 0 |
| 3条撤权/其他用户无权限题返回来源 | 0 | 0 |
| 跨范围/授权违规 | 0 | 0 |
| 平均本地检索耗时 | 19.98ms | 53.91ms |
| 查询本地编码次数 | 0 | 27 |

原始汇总的 `noEvidenceFalseRecallCount=18` 还包含3条跨课程题各返回2份当前课程弱来源（6份），不能把18都说成6条资料不足题的统计。3条无权限题即时返回 NONE、不编码。该集合投影编码18条，加27次查询编码；供应商请求0。

两种模式可回答题都命中预期来源，但语料每课程仅2份文档，来源指标较容易饱和。混合更慢、弱召回未改善，没有证明质量收益；默认保持关闭。资料不足题没有执行真实模型生成，不能报告“拒答率100%”。工具、多轮、取消和记忆使用上述另行自动化回归，不把它们伪装成本30条检索集里的生成验收。

## 费用、未验收项与回退

既有授权真实烟测3次额度已用满，持久台账保留11592 Token预留；成功调用已知324 Token，失败/取消实际用量未知，不能算0或重置额度。阶段7～8没有新增供应商请求。首次真实Mem0闭环两个BGE进程区间共编码13条，SDK add5/delete4，LLM尝试均0；这些是该次诊断计数，不是全项目累计计费。

尚未真实验收：供应商自主选择/组合工具、真实用户非空进度建议、供应商自动提取质量、30条生成答案支持度/正确拒答率，以及真实供应商与浏览器/业务库联合端到端效果。新检索集、受控HTTP Mock和旧实现成绩都不能补足这些缺口；扩展付费验收须另定明确调用与Token额度。

停止长期偏好功能可关闭 memory总开关；带该偏好依赖的旧AI会隐藏，原用户会话文本和备份不因此物理擦除。关闭自动提取仅停止额外提取，明确管理仍可用。关闭 langchain/chat入口可回退新助理；保留新版历史复核器和数据库历史，旧二进制不能理解新依赖信封。向量开关关闭可保留BM25路径，MySQL仍为资料与权限事实源。

经代码和本轮证据支持的项目表述：在JDK17/Boot3.3.5上以普通LangChain4j依赖实现流式多轮课程助理，用MySQL保存正式会话，复用授权RAG和逐次预算，提供受控只读工具与可管理的Mem0偏好；独立隔离基础设施和HTTP Mock回归已通过，真实供应商扩展验收仍保留上述边界。
