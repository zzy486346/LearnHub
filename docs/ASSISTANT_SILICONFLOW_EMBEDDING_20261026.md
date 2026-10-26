# SiliconFlow 远程课程 Embedding 接入

实施日期：2026-10-25～26。基线 `master@a3c1783`；功能分支 `feature/siliconflow-embedding`。保持 JDK 17 / Spring Boot 3.3.5 / LangChain4j 1.22.0，没有新增依赖。

## 接口与范围

按 [SiliconFlow Embeddings API](https://api-docs.siliconflow.cn/docs/api/embeddings-post) 接入 `POST https://api.siliconflow.cn/v1/embeddings`，使用 Bearer API Key。模型按用户选择固定为 `BAAI/bge-large-zh-v1.5`，输出 1024 维浮点向量；不发送仅 Qwen 支持的 `dimensions` 参数。

本次更换课程 RAG 的 Embedding 推理位置，不更换 DeepSeek 聊天生成，也不改变 Mem0 的独立偏好向量空间。课程材料和权限仍以 MySQL 为准，PG/ES 只保存投影。供应商价格、免费额度不由代码保证，Key 仍属于凭据。

## 配置与部署

`.env` 已被 Git 忽略，Key 只放在该文件或环境变量。示例文件只包含空值：

```dotenv
SPRING_PROFILES_ACTIVE=local,vector
LEARNHUB_VECTOR_ENABLED=true
LEARNHUB_VECTOR_HYBRID_ENABLED=true
LEARNHUB_VECTOR_SYNC_ENABLED=true
LEARNHUB_VECTOR_EMBEDDING_PROVIDER=SILICONFLOW
LEARNHUB_VECTOR_SILICONFLOW_API_KEY=填写自己的Key
LEARNHUB_VECTOR_MIN_SEMANTIC_SCORE=0
```

Endpoint 默认 `https://api.siliconflow.cn/v1/embeddings`，超时默认 10 秒，最多允许 30 秒；无需在日常 `.env` 重复填写默认值。生产地址仅允许官方 HTTPS，测试允许回环 HTTP，禁止重定向，防止 Key 被发送到任意配置地址。

旧模型的 `0.815` 门槛没有在远程 large 模型上标定，本次本地配置设0关闭该门槛。关闭门槛仍会召回弱来源，不能据此声称证据充分或回答正确；后续应用独立评测重新标定。

新环境的 PG 初始化会创建两张表。已有环境在仓库根目录执行：

```powershell
& ./scripts/assistant/sync-siliconflow-vector.ps1 -ApplyMigration
```

此脚本只对既有 `learnhub_vector` 课程 PG 执行追加迁移和 DML 授权，然后启动无 HTTP 的受控维护测试进行远程回填。它不重建容器、不重启用户后端、不修改购买权限、不调用聊天生成模型。已有完整远程投影时，去掉 `-ApplyMigration` 可单独核验并补偿同步；重复完整同步不重复编码。

已有 PG 容器不会自动加载新增的初始化挂载，脚本将 SQL 经 stdin 送入既有容器，不要求为了迁移重启数据库。新增远程表需先有 `001_course_embeddings.sql` 的 schema、扩展与更新触发函数。

## 模型隔离与调用保护

| 项目 | LOCAL | SILICONFLOW |
| --- | --- | --- |
| 模型身份 | `bge-zh-v15-q-lc122-t480-o32-v1` | `sf-bge-large-zh-v15-t480-o32-v1` |
| PG 表 | `course_embeddings_bge_zh_v1` | `course_embeddings_sf_bge_large_zh_v1` |
| 维度 | 512 | 1024 |
| 推理 | 本地 ONNX | SiliconFlow HTTPS |

`CourseEmbedding` 统一查询、文档、分片及模型身份；`CourseVectorIndex` 对合法表/模型/维度组合做白名单校验。UUID、PG metadata、过滤条件、MySQL 投影进度和同步锁都使用当前模型身份，迟到清理不会跨模型删数据。原表和旧同步进度保留。

远程路径只加载本地分词器，不加载 ONNX 推理权重。核对 [BAAI 官方 tokenizer](https://huggingface.co/BAAI/bge-large-zh-v1.5/blob/main/tokenizer.json) 后，复用打包资源的相同词表、pre/post processor 和 added tokens，调整 `normalizer.lowercase=true` 与 large 一致。核对时官方文件 SHA-256 为 `7dfbf1966ebf99d471c3796e9b457329d2b2182b817e144f1e904b957745c839`；运行时不下载该文件。

文档每片实际 Token 不超过480，重叠最多32 Token，计算包含 CLS/SEP 且禁用截断；知识块身份保持不变。查询按 [BAAI 模型说明](https://huggingface.co/BAAI/bge-large-zh-v1.5) 添加中文检索前缀，连同问题实际不超过512 Token；超限则在 HTTP 前拒绝并沿检索故障路径回退 BM25，不截断问题冒充完整查询。

每个 HTTP 批次最多32条；请求体上限128 KiB，响应上限1 MiB。严格验证模型名、索引一一对应、1024维有限非零向量和 usage，按 index 重排并归一化。401/429/网络错误不做隐藏重试；投影失败由既有补偿表退避重试，查询故障回退 BM25。错误和日志不携带 Key、供应商正文或课程材料。

聊天请求中沿用 `AssistantIoScope` 的更短截止时间和取消回调，时限覆盖完整响应体。检索出口对 `ASSISTANT_CANCELLED` / `ASSISTANT_DEADLINE_EXCEEDED` 直接透传，不能误作故障降级后继续生成。关闭组件会取消在途 HTTP，并释放分词器与线程池。

## 验证方法

HTTP Mock、模型隔离和真实隔离数据库验证：

```powershell
& ./scripts/assistant/verify-vector.ps1
```

脚本创建本次标记的临时 MySQL/PG、随机回环端口，执行既有本地投影及新增远程适配器测试，并在 finally 校验所有权后清理容器和测试卷。HTTP Mock 验证凭据头、无 dimensions、乱序、超限、非法向量、超时/取消；基础设施验证1024维、迁移重跑、中文注释、DML权限、版本/课时过滤、模型隔离及同步幂等。常规 `mvn test` / `mvn verify` 不会自动执行真实远程回填。

真实维护入口 `SiliconFlowVectorBackfillIntegrationTest` 只能显式开启 `learnhub.vector.siliconflow.backfill=true`，只接受本机课程数据库，禁用调度和聊天/记忆生成。比对全部当前文档/版本/知识块身份集合，并验证材料及旧512维向量前后不变。脱敏回执写入 `.local/siliconflow-vector-backfill.json`，不提交本地配置。

## 回退与未验证边界

设置 `LEARNHUB_VECTOR_EMBEDDING_PROVIDER=LOCAL` 并恢复适用于旧模型的门槛，用户下次正常启动后读取原512维投影；不要删除远程表、旧向量或台账。若旧资料已更新，先让本地模型同步至当前版本。关闭 hybrid 则直接回到 BM25。

真实 API 连通/维度、投影回填或来源过滤，不等于答案准确率、远程模型优于本地、门槛已标定或真实长对话验收。没有使用 DeepSeek 三次烟测额度，不重置原额度台账；未重启用户后端，当前运行 JVM 是否加载新配置不能由 `.env` 内容推定。

实际执行结果在本次交付完成后记录于下节；未执行项不得写成通过。

## 本次执行结果

- 2026-10-25 首次通用文本接口烟测：HTTP 200，模型名匹配、1024维、usage 为13 Token。
- HTTP Mock及隔离MySQL/PG验收27项全部执行，零失败/错误/跳过；包括10项远程适配器测试、2项远程基础设施测试和既有15项本地投影测试。首次执行被环境中断，数据库连接断开；该次失败未算通过。2026-10-26 重跑通过，脚本临时容器/卷清理完成。
- 已有课程PG追加1024维迁移和DML授权成功；本机57份当前资料、273个知识块，远程表273条向量，文档/版本/知识块身份集合精确一致，维度及字段注释已核验。维护入口比对课程材料和旧512维投影前后完全一致。
- 首轮真实回填存在部分远端失败，保留补偿进度；后续只补偿未完成文档，没有清空旧投影或反复编码成功文档。最终完整重复同步新增编码0。
- 同一固定通用查询的24请求独立协议诊断中，21次200、3次400，400返回`code=20015`及通用参数错误；未得到供应商根因确认。后续真实应用出口的24门课程烟测为23次`HYBRID`、1次`BM25_FALLBACK`，全部通过当前权限/课程出口检查。报告明确记录降级，不能说24次远程调用全部成功。
- 最终回执`.local/siliconflow-vector-backfill.json`的`embeddingTexts=23`仅表示最后一次幂等核验中的23次成功查询编码，不代表整轮真实调用总数；之前的回填、失败和诊断调用也实际发生。没有把未知或失败用量记成0，没有使用/重置DeepSeek额度台账。
- 根最终`mvn test`和`mvn verify`均通过：各720项，590项实际执行、130项按开关跳过，零失败/错误；verify完成可执行JAR打包。上述27项隔离基础设施另有实际执行证据，不能把常规跳过计作通过。
- 真实生成质量、large模型相对本地模型的效果、远程语义门槛均未验收。没有启动日常后端或重启用户后端；全量回归中的随机端口测试服务随测试JVM退出清理，未保留测试后端。
