# 学习助理实施记录（2026-10-18）

## 核验起点与阶段 0

接手时分支为 `feature/assistant-rag-agent`，HEAD 为 `73a1764`，工作区干净。已阅读根目录 `AGENT.md` 和分阶段计划，不升级 JDK 17 / Boot 3.3.5，不合并主干。

阶段 0 隔离工程提交 `5042eb9`，8 项 HTTP Mock / Spring 手工 Bean 测试全部通过；依赖、工具回合和取消边界见 [实验报告](../experiments/langchain4j-compat/README.md)。`74ccb8d` 修正文档措辞：未读取凭据不能推断本机缺少凭据。生产 POM 尚未引入 LangChain4j。

## 阶段 1：正式历史与运行持久化

已实现五表、本人会话列表/改名/删除、消息游标分页、运行查询/取消、显式 Fake 连续追问。课程访问复用 `CourseService`，登录检查复用 `AssistantSessionVerifier`，限流/并发/预留额度复用 `AssistantRunGuard`。Fake 明确标注模拟，不调用真实供应商。

MySQL 会话行锁分配消息序号；用户与请求编号唯一约束加请求摘要处理幂等，换会话或问题会拒绝。固定租约、实例令牌及条件更新阻止过期实例写成功答案；恢复任务只把遗留运行标记中断，不自动重试模型。正式助手消息只在成功提交事务中保存，不保存草稿。

发送历史或展示助手正文前，重新核对材料完整字段、版本和课时权限；任一来源失效隐藏整条助手消息。尚未具备可重建快照复核器的结构化工具历史一律不重放。上下文窗口裁剪不会删除正式历史。

最终检查、持久化提交与响应成功共享执行管理器的取消锁。数据库提交成功后不再做可失败的远程检查；断网后通过运行状态与正式历史恢复结果。数据库终态优先于迟到回调。

### 实际验证

- 接手基线：根 `mvn test` / `mvn verify` 均成功，315 项，失败 0、错误 0、跳过 57。
- `scripts/assistant/verify-chat.ps1`：19 项全部通过（真实 MySQL 持久化 10、历史复核 5、Fake 服务 4），失败 0、错误 0、跳过 0。
- 真实 MySQL 验证：迁移重复执行、五表及字段中文 COMMENT、A/B 所有权隔离、跨实例并发、相同幂等键换参数、字符串来源编号往返、取消/删除/过期后的迟到提交拒绝、重新创建服务对象后历史恢复。
- 临时 MySQL 使用 `mysql:8.4`、回环动态端口、独立随机凭据和 tmpfs；脚本按本次 label 核验后清理，无遗留测试容器。没有启动或重启用户后端，没有修改用户业务数据库。
- 修改后根 `mvn verify` 成功（包含完整 test）：336 项，失败 0、错误 0、跳过 67；其中会话 MySQL 的 10 项在普通回归默认关闭，已由上面的隔离脚本实际通过。执行管理器新增 2 项覆盖取消与最终提交竞争、最终复核失败不得提交。
- 真实供应商调用数为 0；新增会话 HTTP 登录全链路与重启进程演示尚待后续系统验收，不把服务层测试称为真实登录验收。

### 使用方法

1. 对目标 MySQL 执行 `sql/migrations/20261018_assistant_chat.sql`，与 `sql/schema.sql` 中五表一致。
2. 本地配置设置 `LEARNHUB_ASSISTANT_CHAT_ENABLED=true`、`LEARNHUB_AI_MODE=FAKE`。此入口默认关闭，不自动修改用户本地配置。
3. 由用户正常启动后端。登录后创建 `POST /api/assistant/conversations`，请求 `{"courseId":课程编号,"title":"Redis学习"}`。
4. `POST /api/assistant/conversations/{id}/runs` 输入 `message` 和新的小写 UUID `clientRequestId`。重复发送相同请求不会创建第二次运行。消息通过 `GET /api/assistant/conversations/{id}/messages` 读取。
5. `POST /api/assistant/runs/{id}/cancel` 取消本人运行；`GET /api/assistant/runs/{id}` 查询终态。所有请求使用 Authorization 请求头，令牌不进入 URL。

Windows 测试命令：

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd '-DargLine=-Djdk.net.URLClassPath.disableClassPathURLCheck=true' verify
.\scripts\assistant\verify-chat.ps1
```

## 未完成与验收边界

阶段 2～8 仍待实施/验收：独立聊天工作区、真实流式、生产 LangChain4j / ChatMemory、框架文档切分与授权检索、Embedding / pgvector、框架自主工具循环、Mem0，以及独立质量评测。已有旧 DeepSeek / Agent 的真实结果不充当新链路验收结果。
