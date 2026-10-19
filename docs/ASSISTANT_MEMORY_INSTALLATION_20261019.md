# 学习偏好记忆安装与验收记录

## 安装前记录

2026-10-19，阶段 6 基线 `bcbc346`，JDK 17 / Boot 3.3.5 不变。本记录先于新镜像拉取、Python 依赖安装及 Mem0 数据库创建；当前尚未安装本轮 Mem0 工件。

- SDK：固定官方 `mem0ai==2.2.1`，tag `v2.2.1` 对应源码提交 `94c3fe9f238f3dbf29c9ce98643bd71eb13077cd`。不使用只有旧 latest 的预制 Mem0 server 镜像，不在容器启动时动态 pip install。
- Python：`python:3.12.13-slim-bookworm`，linux/amd64 manifest `sha256:6e13e65c55e33adf203d77ee371cf8bf5d81bd4902ef07565721f46bf44917af`。构建期固定完整依赖锁并检查依赖；不安装 vector-stores/extras 整包，不启动 Qdrant。
- Java Embedding 桥：`eclipse-temurin:17.0.20.1_1-jre-jammy`，linux/amd64 manifest `sha256:30a8176ab22afd141ad9cd1948ad23bdb9fb7ddae666bad4a1d188d9f479d585`。只复用现有 BGE 512 维模型和 Jar，不引入另一套模型或升级项目 Java 主版本。Linux 原生推理需独立验证，不能沿用 Windows 成绩。
- PG：复用已固定镜像 `pgvector/pgvector:0.8.7-pg16-bookworm@sha256:a97d77306ff47cc1d8add4d000cec2207da2e110746318df3137ce53c337ca30`，使用独立记忆数据库和账号；Mem0 collection 为 `id/vector/payload`，不能与课程向量表共用。SDK 查询 public schema，因此记忆表位于独立库 public，管理员预建扩展与表，运行账号只授 DML。
- 隔离：临时容器/网络/卷均带本轮随机所有权标签；Docker 网络为 internal，Embedding 桥和 PG 不发布宿主机端口，仅 Mem0 REST 测试口绑定 `127.0.0.1` 动态端口。部署候选端口 18280，启用前复核占用。测试结束 finally 清理；本轮不为用户保留后台后端。
- 凭据：Mem0、Embedding 桥、PG 使用分别生成的本地服务凭据；不传宿主机供应商环境变量或 DeepSeek 密钥。关闭 Mem0/DJL 遥测与偏好正文日志，私有接口要求服务认证并再次校验 owner。
- 成本边界：全部记忆写入强制 `infer=false`；Mem0 LLM 指向本地拒绝端点且替换为计数拒绝器，内部真实 LLM 调用上限为零。自动提取先通过 Java 统一预算与 Mock 验证，不能把另一个进程的隐式模型调用漏计。

Java 保存偏好意图、所有权、版本、状态和删除 tombstone；Mem0 是可管理的长期偏好服务。浏览器不能指定 user_id、过滤器、元数据或服务地址。忘记立即阻止后续上下文，远端删除可补偿；删除会话与删除偏好是不同操作。不把 SQLite 历史中所有副本擦除作为承诺。

受限桥先采用对称、无查询前缀 BGE 编码供 Mem0 使用，与阶段 5 带查询前缀的课程检索分别验收。偏好作为不可信数据注入，不能成为课程事实引用或覆盖授权。每次使用/重放历史前重新验证偏好当前版本、删除状态及来源权限。

官方依据：[固定 SDK 发布](https://github.com/mem0ai/mem0/releases/tag/v2.2.1)、[固定依赖定义](https://github.com/mem0ai/mem0/blob/v2.2.1/pyproject.toml)、[Memory 实现](https://github.com/mem0ai/mem0/blob/v2.2.1/mem0/memory/main.py)、[PG 实现](https://github.com/mem0ai/mem0/blob/v2.2.1/mem0/vector_stores/pgvector.py)、[官方 REST](https://github.com/mem0ai/mem0/blob/v2.2.1/docs/open-source/features/rest-api.mdx)。镜像 tag/digest 已由官方 Docker Hub API 只读核验，实际下载/构建/验收结果执行后补充。

## 当前状态

已按上述固定版本构建 Python 薄服务与 Java 向量桥镜像。完整传递依赖由实际下载的 Linux amd64 / CPython 3.12 wheel 生成带 SHA256 的 `services/mem0/requirements.lock`，构建强制 `--require-hashes` 并执行 `pip check`。运行期不安装依赖。Mem0 SDK 仍依赖 qdrant-client 包，但没有启动或使用 Qdrant 服务。

当前 Docker 的 internal 网络没有建立宿主机端口映射，前两次隔离验收在读取 REST 映射端口时失败，服务与向量桥本身正常启动；失败记录保留在 `.local/memory-it-*.json`。验证拓扑增加一个只允许固定 `memory-api:8000` 目标和白名单路径的临时 HTTP relay。Mem0、PG、BGE 仅加入 internal 网络且均不发布端口；只有 relay 同时加入独立入口网络并绑定宿主机 127.0.0.1 随机端口。入口网络具备外网路由，relay 代码不接受任意目标、不跟随重定向、不配置供应商凭据。不能把该拓扑描述成所有容器网络都禁止外网。全部临时资源仍按随机所有权标签清理。

已验证 Java 专项 57 项、Python 无网络回归 9 项、真实 MySQL 会话与偏好批次 43 项（其中 MySQL 33 项），均无失败和跳过。根 `mvn test` 536 项、失败/错误零、跳过 105；跳过的基础设施与真实供应商用例不计通过。前端偏好/聊天 Node 15 项、vue-tsc 和构建通过；Edge Mock 验收 12 项通过，390/1440 视口无横向溢出，临时端口与进程已清理。自动提取验证仍在进行，阶段 7 尚未完成。

`verify-memory.ps1 -IncludeJavaClient` 已通过真实 Mem0 SDK 2.2.1 / Linux BGE / PG 闭环，33 条脚本断言和 1 项 Java 真实客户端测试均通过，零跳过；耗时 86.25 秒。覆盖独立问句召回、A/B 隔离、更新/旧版本拒绝、重复请求、两次 Mem0 重启与持久 tombstone、忘记、DDL 禁止、实际 512 维向量、桥停机 503 及恢复。首次完整功能运行结果保存 [原始脱敏成绩](assistant-memory-infrastructure-first-20261019.json)，前两次基础设施启动失败未抹掉。Mem0 三个进程区间的诊断快照累计 SDK add 5 / delete 4，Java BGE 两个进程区间累计编码 13 条；所有区间 LLM 尝试均为 0，供应商请求为 0。这些是本轮操作计数，不是长期持久成本统计。自有四容器、两临时卷、两个网络和端口 63364 已核验清理。

MySQL 是偏好所有权、版本和同步意图事实源。每用户最多 50 条未删除偏好，并发配额由连接级锁约束；远端调用不占 MySQL 事务。修改立即转 PENDING、忘记立即清除本地正文并设置删除标识，均不再进入后续模型上下文。失败以 30 秒至 1 小时退避补偿，每批最多 20 条。每次补偿发送前重验来源会话与课程访问权。

偏好与 E# 课程证据分离；最多 3 条当前偏好作为不可信学习偏好数据进入模型，不能成为课程事实引用。历史 v2 依赖信封保存偏好快照，重放和每轮调用前回 MySQL 检查版本、删除状态及来源授权；任一失效隐藏整条旧 AI 回答。关闭记忆功能同样阻止重放依赖记忆的历史。Mem0 检索故障时当前问答可不使用长期偏好继续，已有历史依赖复核失败不能放行。

部署前先应用 `sql/migrations/V20261019__assistant_memory.sql`。默认 `LEARNHUB_ASSISTANT_MEMORY_ENABLED=false`；启用时在本地配置填 `LEARNHUB_ASSISTANT_MEMORY_BASE_URL`、独立的 `LEARNHUB_ASSISTANT_MEMORY_SERVICE_TOKEN` 与适当的 `LEARNHUB_ASSISTANT_MEMORY_TIMEOUT`。服务凭据也用于用户 HMAC 标签，轮换需规划投影重建，不能假定旧标签自动迁移。正常登录 `/assistant` 后通过标题内“学习偏好”查看、填写、修改或忘记；本轮不会为用户保留测试服务，也未重启用户后端。

明确写入闭环最终根 `mvn verify` 同为 536 项、105 跳过、失败/错误零（实际执行 431 项）。自动提取尚未计入本次验收。
