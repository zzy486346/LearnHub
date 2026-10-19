# 学习助理向量依赖与隔离验证记录

## 安装前记录

日期：2026-10-19。MySQL 继续作为材料、版本和授权事实源，pgvector 只保存可重建投影。阶段 4 基线为 `f0cf845`，JDK 17 / Boot 3.3.5 保持不变。

- 镜像：`pgvector/pgvector:0.8.7-pg16-bookworm`，固定 linux/amd64 manifest digest：`sha256:a97d77306ff47cc1d8add4d000cec2207da2e110746318df3137ce53c337ca30`。已通过官方 Registry 只读核对，安装前尚未拉取。
- 本机 Docker：linux/x86_64。开发配置候选端口 `127.0.0.1:15432:5432`，启动前重新检测；临时测试改用回环动态端口，避免占用用户服务。
- 开发数据卷候选 `learnhub_vector_data`；本轮不启动常驻开发数据库。测试使用带本次 label 的独立临时卷，测试结束删除容器和卷。
- 数据库 `learnhub_vector`、schema `learnhub_rag`。运行账号 `learnhub_vector_app` 仅有指定 schema/table 的运行权限；迁移身份负责 extension、表和索引，应用不能创建 extension。测试凭据随机生成，禁止输出或提交。
- PG 连接池由向量组件内部持有，不能注册为 Spring `DataSource` bean；不能替换现有 MySQL 自动配置。SQL 独立放在 `sql/postgres/`，不交 MySQL 初始化。

模型固定为 `dev.langchain4j:langchain4j-embeddings-bge-small-zh-v15-q:1.22.0-beta32`，由 BOM 1.22.0 管理；向量存储模块同用该 BOM。SDK Apache-2.0，底层 BAAI/bge-small-zh-v1.5 模型 MIT。模型参数约 24M，输出 512 维、L2 归一化、余弦距离；查询前缀为“为这个句子生成表示以用于检索相关文章：”，资料正文不加查询前缀。

发布源码将 ONNX/tokenizer 放入 JAR，运行时从 ClassLoader 加载；首次 Maven 解析会下载工件，不能把尚未运行称为离线验收。模型 512 Token 上限与阶段 4 的 600 UTF-16 字符不是同一单位，向量投影必须在实际 tokenizer 下分片，不静默截断。换模型/切分版本使用新的投影标识，不能混写不同向量。

资料：[pgvector 固定镜像](https://github.com/pgvector/pgvector#docker)、[Docker 端口发布](https://docs.docker.com/engine/network/port-publishing/)、[BGE 模型卡](https://huggingface.co/BAAI/bge-small-zh-v1.5)、[LangChain4j pgvector](https://docs.langchain4j.dev/integrations/embedding-stores/pgvector/)。

## 当前状态

已解析两个普通 Java 模块并编译，未使用 Starter、未升级 Boot。固定 digest 镜像已成功拉取（首次 EOF，复用缓存重试一次成功）。未创建常驻开发数据库或卷。

`BgeChineseEmbedding` 使用 JAR 内的 ONNX/tokenizer，初始化前强制 `ai.djl.offline=true` 和关闭 DJL tracking；环境 `DJL_OFFLINE=false` 会直接拒绝初始化。最初断网测试捕获到了 DJL 的 EC2 元数据探测，修复后 Java SocketPermission 全拒绝期间初始化和编码通过。原生 DLL 从依赖 JAR 解压到 DJL 缓存，不需要模型服务。SDK 的静态 ONNX 会话没有公开 close，随 JVM 生命周期释放；组件关闭自有 tokenizer，使用同步执行器，不创建 SDK 缓存线程池。这些系统属性在 JVM 内持续生效。

实际 512 维和 L2 范数已验证。正文每片最多 480 Token（含 CLS/SEP），重叠最多 32 Token；保留 Unicode 完整性和覆盖，超限查询拒绝而非截断。模型前缀只用于 query。编码成功文本数可读取 `embeddingCount()`；不等同付费模型请求数。

真实隔离 PG 测试 4 项通过：幂等 UUID 写入、同义问题排序/课程与课时过滤、版本迟到与删除重建、DML 最小账号/禁止 DDL/512 维/中文字段注释、长片段再切分。另有本地模型 3 项通过。此处同义样例只是功能烟测，不是质量提升证据。

复现：在仓库根运行 `powershell -File scripts/assistant/verify-vector.ps1`。脚本只使用已拉取的固定镜像，在回环动态端口创建带随机所有权标记的容器和临时卷，随机测试凭据仅注入子进程；结束清理容器和卷，不启动后端。PG 运行账号只有指定表 DML 和 schema USAGE；扩展、表、注释、更新时间触发器由迁移身份执行 `sql/postgres/001_course_embeddings.sql`。PG 数据源没有注册到 Spring，尚未接入生产召回。

底座提交为 `f460ed2`。后续已实现 MySQL 投影状态/补偿、生产开关与私有 Hikari 池、RRF、删除同步；最终根回归结果见实施记录。

## 生产接入和回滚

1. 对目标 MySQL 执行 `sql/migrations/V20261019__knowledge_vector_projection.sql`；PG 迁移仍单独执行，运行账号只授指定表 DML。同步使用一个专用 MySQL 连接，读取材料还需要一个连接，MySQL 池至少两个可用连接。
2. 正常启动时包含独立 `vector` profile（例如 `--spring.profiles.include=vector`，保留原有 profile），本地填 `LEARNHUB_VECTOR_JDBC_URL`、`LEARNHUB_VECTOR_USERNAME`、`LEARNHUB_VECTOR_PASSWORD`，然后 `LEARNHUB_VECTOR_ENABLED=true`、`LEARNHUB_VECTOR_SYNC_ENABLED=true`，先保持 `LEARNHUB_VECTOR_HYBRID_ENABLED=false`。同步每十秒至多十份文档；材料 ES 投影完成后才进入向量投影。未包含该 profile 时，即使误开 enabled 也不注册向量组件。
3. 仅在本地对比后按需开启 `LEARNHUB_VECTOR_HYBRID_ENABLED=true`。关闭该值立即恢复 BM25；停用 `LEARNHUB_VECTOR_ENABLED` 会在下次正常启动时不注册向量组件。代理没有修改 `.env` 开关或重启用户服务。
4. PG 连接池不是 Spring `DataSource` Bean，最多三连接、最小零连接，连接超时两秒、socket 超时五秒，按需初始化；组件销毁时关闭自有池和 tokenizer。关闭功能不能在运行中的 JVM 卸载 SDK 静态 ONNX。
5. 投影重建入口为 `VectorProjectionSync.requeueAll()`，由受控维护调用触发，没有开放无鉴权 HTTP 接口。PG 表/数据丢失后需先以迁移身份恢复表，再重新排队。当前不自动检测人工清空；不能把空 PG 当作已完成重建。

MySQL `GET_LOCK` 在实例间串行化同库/模型同步；逐知识块前、全量完成前及 CAS 更新中复核当前版本。已标完成而尚未清理旧代次时保留 `cleanup_pending`，进程重启后可补偿；失败按 30 秒至一小时退避。逻辑删除在短 MySQL 行锁内删除 PG，再更新 REMOVED，失败可幂等重试，不宣称跨库原子事务。

搜索只接受服务端课程/课时与 MySQL 当前已完成投影版本快照，版本条件在 PG 排序前生效；最多一千文档，超过整体回退 BM25。版本 OR 使用平衡树，已实际验证一千版本过滤。分片候选窗口扩大后按知识块去重，RRF k=60、稳定排序、身份冲突丢弃；最后仍统一回 MySQL 复核正文/版本并再次计算权限。PG 故障回退 BM25；ES 故障直接返回检索不可用，不能用未完成回填的向量证明资料不足。

## 首次冻结开发集结果

原始结果原样保留于 [首次开发集记录](assistant-vector-development-first-20261019.json)。使用既有已见 8 条开发题、6 讲义/18 场景；不是独立留出。真实隔离 MySQL/ES/PG，零供应商调用。

| 来源代理指标 | BM25 | HYBRID |
| --- | ---: | ---: |
| 可回答题来源 Recall@5 | 6/6 | 6/6 |
| 2 条无证据题错误召回来源总数 | 4 | 4 |
| 平均检索耗时（毫秒） | 36.303875 | 97.7088875 |
| 查询本地 Embedding 数 | 0 | 8 |

投影本地 Embedding 共 18 条，跨课程/课时越权和禁止来源均为零。无证据题两种模式都存在弱召回；这不是生成答案错误率，也不能证明正确拒答。混合没有显示来源质量收益且更慢，默认保持关闭，未调整该冻结集或阈值掩盖首次成绩。答案支持度、错误拒答和新的独立留出仍属阶段 8 验收缺口。
