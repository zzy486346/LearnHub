# 本地服务部署

在仓库根目录执行，真实配置只保存在 Git 忽略的 `.env`。基础服务、记忆服务和课程向量服务统一归入 `deploy`，各自使用独立数据卷。

## 服务用途与精简

| 服务 | 用途 | 日常保留建议 |
| --- | --- | --- |
| mysql | 业务数据、课程正文、权限、会话和消息历史 | 保留 |
| redis | 登录与缓存、点赞/秒杀状态、助理限流和预算保护 | 保留 |
| rabbitmq | 点赞、秒杀等异步消息 | 使用完整业务时保留 |
| elasticsearch | 课程搜索、RAG 的 BM25 检索 | 保留 |
| course-vector-pg | 课程资料向量投影，支持语义与混合检索 | 使用向量检索或同步时保留 |
| memory-api | Mem0 学习偏好读写与检索 API | 使用长期记忆时保留 |
| memory-pg | Mem0 学习偏好的向量数据库 | 与 memory-api 配套 |
| memory-bridge | 为 Mem0 提供本地 BGE 向量编码 | 与 memory-api 配套 |
| memory-loopback | 将宿主机回环端口 18280 转发到私网 Mem0 API | 宿主机运行 Java 并使用 Mem0 时保留 |
| kibana | ES 可视化调试界面，业务服务不依赖 | 默认关闭，tools profile 按需启动 |

Kibana 已改为可选服务。普通 `up -d` 不会新启动它；已有运行容器需明确停止。停止不会删除数据卷，后续需要 ES 管理界面时可恢复：

```powershell
docker compose -p deploy -f deploy/docker-compose.yml stop kibana
docker compose -p deploy -f deploy/docker-compose.yml --profile tools up -d kibana
```

`memory-*` 的四个容器构成一项长期记忆功能，不能只删除其中一个。若整项不用，先在 `.env` 将 `LEARNHUB_ASSISTANT_MEMORY_ENABLED=false`，正常重启后端后，再按 [记忆服务说明](../services/mem0/README.md) 停止四个容器；保留数据卷。课程向量库与记忆库用途和模型编码约定不同，本次不做数据库合并。

2026-10-20 已将原本依赖本地 Nginx 的 21 个封面和 42 个视频迁移至 OSS，数据库中 8091 引用为 0；`course-media` 容器、Compose 服务和 Nginx 配置已移除，后端通过媒体资源 ID 生成访问地址。见 [媒体迁移记录](../docs/COURSE_MEDIA_OSS_MIGRATION_20261020.md)。

## 课程向量数据库

```powershell
docker compose --env-file .env -p deploy -f deploy/docker-compose.yml -f deploy/docker-compose.memory.yml -f deploy/docker-compose.vector.yml up -d --wait course-vector-pg
```

此命令只启动或更新课程向量容器，不重建已有 MySQL、Redis、ES 或 Mem0。`course-vector-pg` 使用固定 digest 的 pgvector 0.8.7 / PostgreSQL 16 镜像，只开放 `127.0.0.1:15432`。它与 Mem0 的 PostgreSQL 容器分开，数据库为 `learnhub_vector`，schema 为 `learnhub_rag`。

首次空卷启动自动执行 `sql/postgres/001_course_embeddings.sql`，创建 vector 扩展、512 维投影表、中文注释和更新触发器，再创建独立运行账号；Java 运行账号仅有指定表 DML、schema USAGE 和数据库 CONNECT 权限，不能创建扩展、表或数据库。

- `LEARNHUB_VECTOR_ADMIN_PASSWORD`：Docker 首次初始化管理员密码。
- `LEARNHUB_VECTOR_USERNAME` / `LEARNHUB_VECTOR_PASSWORD`：Java 投影写入和检索的独立运行账号。
- `LEARNHUB_VECTOR_JDBC_URL`：宿主机 Java 默认连接 `jdbc:postgresql://127.0.0.1:15432/learnhub_vector`。
- 后端需要包含 `vector` profile，向量连接、同步和混合检索由三个独立开关控制；配置生效需用户正常启动或重启后端。

已有数据卷不会因修改初始化环境变量而自动变更密码。投影可由 MySQL 资料重建，但删除 PG 数据卷后须重新执行迁移，并通过受控投影重建流程回填；不要把空库误认为完整向量索引。课程正文和权限仍以 MySQL 为准，Mem0 只保存学习偏好。

## 与记忆服务一起管理

完整服务定义可以同时传入三份 Compose 文件；定向指定服务名可避免不必要的更新。记忆卷创建与旧项目迁移见 [Mem0 部署说明](../services/mem0/README.md)。

2026-10-20 已实际创建课程向量容器，扩展版本 0.8.7、表及中文注释核验通过；原有 10 个容器的 ID 和启动时间保持一致。已回填 57 份当前资料、273 个片段、273 条 512 维向量，详见 [回填与评测记录](../docs/ASSISTANT_VECTOR_LOCAL_ACCEPTANCE_20261020.md)。

## 回填及增量同步

在仓库根目录执行维护验证命令：

```powershell
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' -pl learnhub-server -am '-Dtest=LocalHybridBackfillSafetyTest,LocalHybridBackfillIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.vector.local.backfill=true' test
```

它仅接受本机 MySQL/ES/Redis 和 `127.0.0.1:15432/learnhub_vector`；不启动 HTTP 服务，禁用测试上下文全部定时任务及业务启动迁移，不创建购买权限，不调用生成模型。先核对 ES 当前代次，再由本地 BGE 向量化并通过既有投影补偿器回填。当前文档/版本/片段身份集合必须与 PG 精确一致。重复运行验证幂等性，首轮回执保留不覆盖，最新回执写入 Git 忽略的 `.local/vector-local-latest.json`。

日常后端读取 `.env` 的 `SPRING_PROFILES_ACTIVE=local,vector`，以及 `LEARNHUB_VECTOR_ENABLED=true`、`LEARNHUB_VECTOR_SYNC_ENABLED=true`。启动后每 10 秒批量补偿待同步资料，新增/更新资料仍通过原有导入入口写入 MySQL，禁止直接把 PG 当资料事实源。Java 进程使用私有 PG 连接池，不替换 MySQL DataSource。

`LEARNHUB_VECTOR_HYBRID_ENABLED` 控制 BM25 + 中文 BGE + RRF（k=60），示例默认关闭，本机以 `.env` 实际值为准。2026-10-21 新增 `LEARNHUB_VECTOR_MIN_SEMANTIC_SCORE=0.815`：按原始语义分数过滤混合候选，RRF只负责排序；设为0可关闭门槛。门槛不作用于纯BM25或向量异常降级，不能替代最终证据充分性判断。标定结果、误过滤与复跑命令见 [阈值记录](../docs/ASSISTANT_RETRIEVAL_THRESHOLD_20261021.md)。代理没有重启用户后端，配置在用户下次正常启动时生效，无需重新回填。
