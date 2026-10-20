# 本地环境配置核对（2026-10-20）

本次直接核对根目录 `.env`、`.env.example`、Java 配置和两份 Compose。只调整注释及分组，所有已有配置值保持一致；未启动或重启任何服务。

## 当前状态与缺项

`.env` 有 32 项，均非空、无重复；相比 `.env.example` 缺少 49 项。缺少显式配置不等于缺少代码默认值，也不代表已经完成真实连通验收。

| 用途 | 当前状态及所需配置 |
| --- | --- |
| 基础连接与对象存储 | MySQL、Redis、RabbitMQ、ES、OSS 配置项已有值；本次只检查配置，不验证连接或凭据有效性。 |
| DeepSeek | `LEARNHUB_AI_MODE=DEEPSEEK`，模型名及 API Key 已填写；本次未调用供应商。 |
| 独立聊天 | `LEARNHUB_ASSISTANT_CHAT_ENABLED=true`；会话表迁移仍是运行前提。 |
| LangChain4j | 缺少 `LEARNHUB_ASSISTANT_LANGCHAIN_ENABLED`，默认 false；使用该适配器需显式设为 true。 |
| Mem0 Java 接入 | 缺少 `LEARNHUB_ASSISTANT_MEMORY_ENABLED`、`LEARNHUB_ASSISTANT_MEMORY_SERVICE_TOKEN`；总开关默认 false，启用时应设 true 并填写与容器一致的服务令牌。BASE_URL、TIMEOUT、AUTO_EXTRACT_ENABLED 可先沿用示例默认值。 |
| Mem0 Docker | 必填且尚未填写：`LEARNHUB_MEMORY_PG_ADMIN_PASSWORD`、`LEARNHUB_MEMORY_PG_PASSWORD`、`LEARNHUB_MEMORY_EMBEDDING_TOKEN`，以及上行的 `LEARNHUB_ASSISTANT_MEMORY_SERVICE_TOKEN`。这些应为独立本地凭据。`LEARNHUB_MEMORY_LOCAL_PORT` 可沿用默认 18280。 |
| 课程向量 | 六项 `LEARNHUB_VECTOR_*` 均未配置，功能默认关闭；需独立 PostgreSQL/pgvector、对应迁移、`local,vector` profile 及连接凭据。与 Mem0 的 `learnhub_memory` 数据库分开，混合检索可保持关闭。 |
| 其他缺项 | 助理预算、模型 HTTP 限制、订单超时、秒杀/点赞补偿多数可沿用代码默认值。JWT 文件路径未在 `.env` 中显式配置，需按实际运行环境的签名密钥加载方式核对，不能用空路径代替有效密钥。 |

仅添加配置不会创建数据库、执行迁移或启动容器。完整无真实凭据模板见根目录 `.env.example`，不要用模板覆盖已有 `.env`。

## Docker 如何读取

在仓库根目录 PowerShell 执行：

```powershell
Set-Location D:\Project\myproject\LearnHub
# 基础服务（不会额外创建 Mem0 或课程向量库）
docker compose --env-file .env -f deploy/docker-compose.yml up -d
# 填好上述 4 项 Mem0 必填凭据，且本地服务镜像准备好后执行
docker compose --env-file .env -f services/mem0/compose.yml up -d --wait
```

`--env-file` 提供 Compose 的变量替换来源，只有 Compose 显式引用的变量才起作用，不会把整份 `.env` 自动注入每个容器。现有基础 Compose 的 MySQL 和 RabbitMQ 账号、端口部分写在 YAML 中，因此不能仅修改 Java 的连接参数来更改容器账号。已有数据库卷的密码也不会因修改初始化环境变量自动改变。

Mem0 Compose 已引用上述凭据，包含私有 PostgreSQL/pgvector、向量桥、记忆 API 与本机入口。它只发布本机 API 端口，PG 不发布宿主机端口；课程向量库不由该 Compose 创建。缺少四项必填凭据时 Compose 会在变量校验阶段报错。镜像构建、迁移和完整验收说明见 `services/mem0/README.md` 与 `docs/ASSISTANT_FINAL_ACCEPTANCE_20261019.md`。

## 本次验证范围

逐项比对编辑前后赋值行：本地 32 项及示例 81 项的名称和值均保持一致，只改变注释和分组；检查重复键、Git 忽略状态和文档差异。此次没有执行数据库迁移、供应商调用或服务连通测试。

## 缺少显式配置的完整键名清单

以下仅列键名，不包含本地配置值；按模板分组，带默认值的项目可不填写。

### 秒杀、点赞与异步补偿

- `LEARNHUB_SECKILL_MAX_PUBLISH_ATTEMPTS`
- `LEARNHUB_SECKILL_CONSUMER_CONFIRMATION_TIMEOUT`
- `LEARNHUB_SECKILL_RESERVATION_ORPHAN_TIMEOUT`
- `LEARNHUB_SECKILL_RECONCILIATION_ENABLED`
- `LEARNHUB_SECKILL_RECONCILIATION_DELAY`
- `LEARNHUB_LIKE_FLUSH_ENABLED`
- `LEARNHUB_LIKE_FLUSH_DELAY`
- `LEARNHUB_LIKE_PUBLISH_RETRY_ENABLED`
- `LEARNHUB_LIKE_PUBLISH_RETRY_DELAY`
- `LEARNHUB_LIKE_RECONCILIATION_ENABLED`
- `LEARNHUB_LIKE_RECONCILIATION_DELAY`
- `LEARNHUB_LIKE_LEGACY_KEY_MIGRATION_ENABLED`
- `LEARNHUB_LIKE_PUBLISHER_CONFIRM_TIMEOUT`

### 支付与订单

- `LEARNHUB_ORDER_PENDING_TIMEOUT`

### JWT 签名密钥位置

- `LEARNHUB_JWT_PRIVATE_KEY_PATH`
- `LEARNHUB_JWT_PUBLIC_KEY_PATH`

### DeepSeek 模型与 HTTP 限制

- `LEARNHUB_AI_MAX_OUTPUT_TOKENS`
- `LEARNHUB_AI_REQUEST_TIMEOUT`
- `LEARNHUB_AI_CONNECT_TIMEOUT`
- `LEARNHUB_AI_MAX_INPUT_BYTES`
- `LEARNHUB_AI_MAX_RESPONSE_BYTES`
- `LEARNHUB_AI_TRANSPORT_THREADS`

### Mem0 学习偏好访问

- `LEARNHUB_ASSISTANT_MEMORY_ENABLED`
- `LEARNHUB_ASSISTANT_MEMORY_BASE_URL`
- `LEARNHUB_ASSISTANT_MEMORY_SERVICE_TOKEN`
- `LEARNHUB_ASSISTANT_MEMORY_TIMEOUT`
- `LEARNHUB_ASSISTANT_MEMORY_AUTO_EXTRACT_ENABLED`

### 独立学习助理、LangChain4j 与预算

- `LEARNHUB_ASSISTANT_TIMEOUT`
- `LEARNHUB_ASSISTANT_MAX_MODEL_CALLS`
- `LEARNHUB_ASSISTANT_MAX_TOOL_ROUNDS`
- `LEARNHUB_ASSISTANT_MAX_TOOL_CALLS`
- `LEARNHUB_ASSISTANT_MAX_RUN_TOKENS`
- `LEARNHUB_ASSISTANT_MAX_OUTPUT_TOKENS`
- `LEARNHUB_ASSISTANT_GLOBAL_CONCURRENCY`
- `LEARNHUB_ASSISTANT_REQUESTS_PER_MINUTE`
- `LEARNHUB_ASSISTANT_DAILY_MODEL_CALLS`
- `LEARNHUB_ASSISTANT_DAILY_RESERVED_TOKENS`
- `LEARNHUB_ASSISTANT_MAX_INPUT_BYTES`
- `LEARNHUB_ASSISTANT_LANGCHAIN_ENABLED`

### 课程向量 PostgreSQL / pgvector

- `LEARNHUB_VECTOR_ENABLED`
- `LEARNHUB_VECTOR_HYBRID_ENABLED`
- `LEARNHUB_VECTOR_SYNC_ENABLED`
- `LEARNHUB_VECTOR_JDBC_URL`
- `LEARNHUB_VECTOR_USERNAME`
- `LEARNHUB_VECTOR_PASSWORD`

### Mem0 Docker 私有依赖

- `LEARNHUB_MEMORY_PG_ADMIN_PASSWORD`
- `LEARNHUB_MEMORY_PG_PASSWORD`
- `LEARNHUB_MEMORY_EMBEDDING_TOKEN`
- `LEARNHUB_MEMORY_LOCAL_PORT`
