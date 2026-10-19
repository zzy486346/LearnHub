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

当前仅完成阶段 5 的本地模型和 PG 投影底座。MySQL 投影状态/补偿、生产开关与独立连接池、RRF 接入、删除同步及冻结集对比仍待完成，不能称为混合检索已交付。
