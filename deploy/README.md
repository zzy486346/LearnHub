# 本地服务部署

在仓库根目录执行，真实配置只保存在 Git 忽略的 `.env`。基础服务、记忆服务和课程向量服务统一归入 `deploy`，各自使用独立数据卷。

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

2026-10-20 已实际创建课程向量容器，扩展版本 0.8.7、表及中文注释核验通过；原有 10 个容器的 ID 和启动时间保持一致。全量回填和冻结新语料评测另行记录。
