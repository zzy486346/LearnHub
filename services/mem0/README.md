# 私网学习偏好 Mem0 服务

固定 `mem0ai==2.2.1`，使用 SDK 的真实 Memory 与 pgvector；本服务不是托管 Mem0 Platform，也不启动官方 Dashboard。仅部署一个 Uvicorn worker；单进程 RLock 保证 journal 与 SDK 操作串行，不支持多副本共享 SQLite。

先按仓库的 `docs/ASSISTANT_MEMORY_INSTALLATION_20261019.md` 记录与核验依赖。`requirements.in` 是锁定输入，构建需要由验证流程生成完整带哈希的 `requirements.lock`，禁止虚构哈希或运行时安装依赖。不得把 `.env`、服务令牌或数据库密码加入镜像层。

## 环境变量

| 变量 | 含义 |
| --- | --- |
| `LEARNHUB_MEMORY_SERVICE_TOKEN` | Java → Mem0 的独立随机令牌，至少 32 字符 |
| `LEARNHUB_MEMORY_PG_HOST` | 私网 PG DNS 名称 |
| `LEARNHUB_MEMORY_PG_PORT` | 默认 5432 |
| `LEARNHUB_MEMORY_PG_DATABASE` | 独立记忆数据库 |
| `LEARNHUB_MEMORY_PG_USER` | 只授 DML 的运行账号 |
| `LEARNHUB_MEMORY_PG_PASSWORD` | 账号密码，仅本地配置 |
| `LEARNHUB_MEMORY_EMBEDDING_BASE_URL` | 私网 Java 桥的 OpenAI 兼容 `/v1` 基地址 |
| `LEARNHUB_MEMORY_EMBEDDING_TOKEN` | Mem0 → Java 桥的独立随机令牌 |
| `LEARNHUB_MEMORY_DATA_DIR` | 默认 `/data`，存 journal 与 SDK SQLite 历史 |

管理员在独立 PG 数据库执行 `mem0_collection.sql`，按部署账号授 `public` USAGE 与固定表 SELECT/INSERT/UPDATE/DELETE。服务启动先只读核对预建向量列维度为 512；缺失即失败，不向运行账号授扩展或建表权限。PG 池最多 3 条连接，连接/取连接上限 3 秒。本地 Embedding HTTP 5 秒且无 SDK 重试。Mem0 使用对称无前缀 BGE，与课程检索投影分开。

## 内部接口

全部接口要求 `Authorization: Bearer <服务令牌>`。不对浏览器发布，仅 Java 使用；ownerKey 是 Java 生成的 64 位小写十六进制用户 HMAC，不是真实用户 ID。正文禁止额外字段。

| 方法与路径 | 输入与响应 |
| --- | --- |
| `PUT /preferences/{UUID}` | `{ownerKey,version,category,text}` → `{preferenceId,version}` |
| `DELETE /preferences/{UUID}` | JSON `{ownerKey,version}` → `{preferenceId,version,deleted:true}` |
| `POST /preferences/search` | `{ownerKey,query,limit}` → `{items:[{preferenceId,version}]}` |
| `GET /preferences?ownerKey=...&limit=...` | `{items:[{preferenceId,version,category,text}]}` |
| `GET /health` | 进程就绪、提取关闭；不代表外部依赖持续可用 |
| `GET /stats` | 本进程操作、SDK add/delete 和拒绝 LLM 尝试计数 |

version 为 1～2147483647 的整数；category 限 STYLE/PACE/LEVEL/GOAL；正文和搜索问题 1～200 字符且非空白。搜索 limit 1～12，查看 limit 1～50。错误：401 服务身份无效；404 标识属于其他用户；409 旧版本/同版本不同正文/已删除 ID；422 字段校验失败；503 记忆依赖不可用。没有 configure/reset/history/delete_all 接口。

## 版本、幂等和故障

写入先把 owner、逻辑 ID、最高版本、正文摘要和 PENDING 状态持久化到 `service_state.sqlite`，再向 Mem0 `add(...,infer=False)`。同版本同正文重试可复用已经落库但响应丢失的 SDK 记录；清理旧版本及重复记录后才 ACTIVE。PENDING 与孤儿记录不参与召回。修改采用新 SDK 记录替换旧记录，没有跨 SQLite/PG 伪事务。SDK 扫描按固定用户、应用、种类和逻辑 ID 过滤，最多 1000 条，触顶返回失败，不假装扫描完整。

删除要求新的版本；先持久化 DELETED tombstone，再清理远端。远端失败时不再召回，重复同版本删除会继续补偿。已删除逻辑 UUID 永不复活，重新记住使用新 UUID。所有 SDK 按 ID 删除前重新获取并校验 owner/app/kind/preference_id。召回返回后复核 journal ACTIVE、精确版本、remote ID 和正文摘要，Java 仍须再验证本人、来源与当前版本。

SDK 正式正文在 Mem0 中，journal 不保存原文。SDK SQLite 历史会保留历史内容；“忘记后不再召回”不等于物理清除所有历史副本。`stats` 是进程内运行诊断，不是持久成本台账；Java 负责正式操作与提取预算。

## 模型与生命周期

全部写入强制 `infer=False`。LLM 初始化只配置本地拒绝 URL 和无外部效力占位值，随后替换为只计数并拒绝的对象；不接收供应商密钥，不启用视觉、reranker 或 procedural memory。`MEM0_TELEMETRY=false` 在导入前生效，SDK 日志提高到 WARN。实际自动提取由 Java 统一预算保护，本服务不隐式提取。

FastAPI lifespan 显式关闭 Memory、OpenAI 客户端、PG 池和 journal。容器使用 internal Docker 网络，PG 和桥不发布端口，仅临时 Mem0 测试 REST 发布 `127.0.0.1` 动态端口。测试脚本必须记录所有权并 finally 清理。

## 验证

## 本地启动与停止

先在仓库根运行后端 `mvn verify`，再执行 `./scripts/assistant/build-memory-images.ps1`。构建固定的 `learnhub-memory-bridge:local`、`learnhub-mem0:local`，只复制 Jar 到临时最小上下文，结束核验路径后删除构建目录，不启动用户后端。

在被 Git 忽略的根 `.env` 中配置示例已有的独立记忆库管理员密码、运行账号密码、向量桥凭据和 `LEARNHUB_ASSISTANT_MEMORY_SERVICE_TOKEN`，不要复用供应商密钥。服务凭据至少32位，桥凭据至少24位可见ASCII；建议使用独立随机值。Compose 只显式转交所需配置，不把整份 `.env` 或供应商密钥传入容器。

```powershell
docker compose --env-file .env -f services/mem0/compose.yml up -d --wait
```

初次启动自动预建独立 PG 数据库、扩展和固定表，创建仅有 DML 的 memory_runtime 账号；后续数据保存在两个命名卷。仅固定目标回环入口发布 `127.0.0.1:18280`（可配置），三个私有服务不发布端口。入口双网络具有外网路由但不支持任意目的地址，只适合本机开发，不作为公开生产网关。Java 桥只读根文件系统，私有 `/tmp` 必须允许 exec，以加载 DJL/ONNX 原生库。初次初始化失败要先查原因，不能盲目删除数据卷。

后端启用前应用两个 `V20261019__assistant_memory.sql` / `V20261019__assistant_preference_extraction.sql` MySQL 迁移（均可重复执行），再在本地配置启用 `LEARNHUB_ASSISTANT_MEMORY_ENABLED=true`，保持自动提取默认关闭。自行正常启动后端，登录 `/assistant` 使用“学习偏好”。开启自动提取另设 `LEARNHUB_ASSISTANT_MEMORY_AUTO_EXTRACT_ENABLED=true`，它会额外消耗统一模型额度；本轮未执行真实供应商提取。

停止本地记忆容器、保留数据卷：

```powershell
docker compose --env-file .env -f services/mem0/compose.yml down
```

自动化验收分别运行 `./scripts/assistant/verify-memory.ps1 -IncludeJavaClient`、`./scripts/assistant/verify-chat.ps1 -IncludeMemory`。`./scripts/assistant/verify-memory-compose.ps1` 验证上述实际 Compose，使用随机项目/动态端口/临时凭据，核验所有权后清理自己的卷与网络，不操作日常开发实例。

依赖准备后在本目录执行 `python -m unittest -v test_app`，覆盖服务认证、严格字段、A/B 隔离、同版本冲突、旧版本拒绝、丢响应后的重启补偿、删除故障 tombstone、伪造远端结果及拒绝提取计数。真实 Linux BGE/PG/Mem0 验收另由仓库脚本执行。此 README 不声明尚未执行的测试通过。
