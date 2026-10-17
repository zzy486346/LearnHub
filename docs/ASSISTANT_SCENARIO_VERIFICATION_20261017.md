# 学习助理 RAG 与 Agent 场景验收记录

> 真实模型续验已完成，最新调用数、首次留出失败项与本地启用方式见 [DeepSeek 续验报告](ASSISTANT_DEEPSEEK_LIVE_ACCEPTANCE_20261017.md)。下文保留凭据到位前的阶段记录，原有“缺 Key / 调用 0”不代表最新状态。

记录日期：2026-10-17。

本文记录阶段 F 的可复现场景验收。它把真实基础设施、脚本化模型和真实模型调用明确分开，避免把本地编排通过误写成真实模型已经自主完成任务。

## 1. 验收边界

- MySQL 使用本机 LearnHub 真实业务库，只读取已有 ACTIVE 用户、课程、目录和本人学习进度，不创建购买、不修改进度。
- Elasticsearch 使用现有 `learnhub-knowledge-search` 与课程搜索索引，材料正文继续经过课程权限和当前版本复核。
- Redis 使用随机前缀 `learnhub:assistant:{scenario-UUID}:` 隔离并发许可、频率和每日预算；测试前后只清理该前缀。
- `ChatModelClient` 在场景测试中由脚本化 MOCK 替换。脚本只根据模型请求历史选择下一项工具或返回结构化结果，每次上报 `prompt=10、completion=10、total=20`。
- `learnhub.ai.mode=FAKE` 是显式的安全配置，保证场景测试不会因本地 `.env` 存在密钥而产生付费调用。最终响应的 `modelMode=MOCK` 继续提示这是脚本模型。
- 20 条留出规格不提供给脚本模型，也不用于调整工具顺序。本轮留出生成质量执行数为 0。

## 2. 场景与断言

### 2.1 结合本人进度生成学习建议

输入是登录用户、课程 `12002` 和学习建议目标。测试从真实数据库选择一名能够读取课时 `32021`、但在该课程尚无进度记录的 ACTIVE 用户，不写入任何业务数据。

脚本模型按以下顺序选择工具：

1. `get_course_outline`
2. `get_my_learning_progress`
3. `search_course_materials`

工具输出进入本次 `EvidenceBook`。最终 FACT 必须引用本次有效证据，课程搜索/目录事实可引用 COURSE/OUTLINE，讲解事实由 MATERIAL 支撑；SUGGESTION 必须同时引用 PROGRESS 和 OUTLINE。测试把 PROGRESS 快照与 `learning_progress` 的真实查询结果对照，并明确覆盖“尚无学习记录”的正常场景。

为什么这样设计：目录说明“接下来能学什么”，本人进度说明“学到了哪里”，材料说明课程事实。三类证据职责不同，建议缺少进度或目录任一项都会被服务端拒绝。

### 2.2 不同的模型工具顺序

第二个输入要求先搜索同主题课程，再查看当前课程目录。脚本模型选择：

1. `search_courses`
2. `get_course_outline`

最终结果引用 COURSE 和 OUTLINE。这个场景证明受控循环接受模型返回的不同白名单工具序列，并逐次执行、计数和回传观察结果；它不证明真实 DeepSeek 已完成自主工具选择。

### 2.3 单次真实 DeepSeek 烟测

`DeepSeekLiveSmokeTest` 默认跳过。只有显式设置 `learnhub.assistant.deepseek.smoke=true` 且本地环境变量或 `.env` 存在 DeepSeek API Key 时才执行。测试固定使用 `deepseek-flash`，最多输出 128 Token，请求超时 10 秒，只调用 1 次且不重试；日志只记录调用数、模型、上限和 usage，不打印密钥或响应正文。

这个烟测验证真实 HTTP 基本契约和 usage，不代替 RAG 回答支持度、真实 Tool Calling 或 20 条留出质量验收。

## 3. 执行命令

从仓库根目录运行默认回归；场景测试因 system property 未开启而跳过：

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd test
D:\Android\apache-maven-3.9.10\bin\mvn.cmd verify
```

在 MySQL、Redis、Elasticsearch 已健康且阶段 A 材料已经受控导入后，显式执行场景测试：

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd -pl learnhub-server -am `
  -Dlearnhub.assistant.scenario.it=true `
  -Dtest=AssistantScenarioIntegrationTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

真实 DeepSeek 烟测仍需同时满足本地凭据条件：

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd -pl learnhub-server -am `
  -Dlearnhub.assistant.deepseek.smoke=true `
  -Dtest=DeepSeekLiveSmokeTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 4. 本轮实际执行记录

| 验收项 | 实际执行数 | 结果 |
| --- | ---: | --- |
| 真实只读工具 | 5 | 两条场景通过：3 + 2；真实 MySQL、ES、Redis |
| 脚本化 MOCK 模型 | 6 | 两条场景各 3 次，不是供应商调用 |
| 真实 DeepSeek | 0 | 显式开启烟测，但本地缺 Key，assumption 跳过；未通过真实验收 |
| 20 条留出生成质量 | 0 | 未执行，不以 MOCK 场景代替 |

场景测试实际执行 2 项，失败 0、跳过 0；同时 Agent 单测 8 项、工具单测 8 项均通过。显式烟测与 MVC 批次 6 项中实际执行 5 项，烟测跳过 1 项。模型自主选择的真实质量、非空学习进度场景及 20 条留出评测仍未验收。完整构建结果见 [最终验收](ASSISTANT_RAG_AGENT_VERIFICATION_20261017.md)。只有真实 DeepSeek RAG、真实 Tool Calling 和留出人工支持度验收完成后，才能把“真实模型自主调用工具并生成学习建议”写成已验证事实。
