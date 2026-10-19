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

仅完成安装前记录；尚未创建本轮记忆服务、连接池、数据库/账号或执行 Mem0 CRUD。阶段 7 未完成。
