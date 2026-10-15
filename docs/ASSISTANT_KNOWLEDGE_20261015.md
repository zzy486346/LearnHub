# 课程学习助理：知识检索第一阶段

本阶段实现模型接口基础与授权知识检索。输入是管理员审校并明确关联课时的场景文本，输出是登录用户有权读取的知识片段与来源。尚未实现模型生成问答、Agent 工具循环、MCP、向量检索或聊天页面。

## 1. 阅读与学习顺序

1. `assistant/model/ChatModelClient`：一次文本输入/输出契约；默认禁用，Fake 明确返回 `[FAKE]`，不会回显输入或发送网络请求。
2. `assistant/knowledge/KnowledgeStore`：在数据库事务内导入文档及知识块。`sourceKey` 是稳定来源标识；相同内容幂等，标题、关联或场景变化时升版本。
3. `KnowledgeChunker`：优先保留场景边界；超长场景按最多 600 个 Unicode 代码点拆分，不机械增加重叠。`chunkOrder` 是文档内从 0 开始的位置，heading 保留原场景标题，不包含视频秒级定位。
4. `KnowledgeIndexSyncService`：事务外构建 Elasticsearch 投影，MySQL advisory lock 防止多个补偿任务并行写同一知识索引。全部知识块写入、旧版本清理及 refresh 成功后，才按当前版本条件回写已索引状态。
5. `KnowledgeRetrievalService`：计算课时读取范围、BM25 搜索、再次检查当前课时范围、批量复核材料版本；正文来自 MySQL。用户身份来自登录 principal。

实现位于 `learnhub-server/src/main/java/com/learnhub/assistant/`。复用现有 ES RestClient、课程访问语义与认证配置，未增加依赖或升级 Spring Boot。

## 2. 数据库与配置

新安装数据库使用 `sql/schema.sql`。已有数据库在备份并确认目标环境后执行：

```sql
-- 在明确选定的 LearnHub 数据库中执行，脚本本身不切换数据库。
SOURCE sql/migrations/V20261015__knowledge_documents.sql;
```

两张表为 `knowledge_document` 和 `knowledge_chunk`。所有表及列都有中文 COMMENT；片段保留所属文档和版本。当前只支持 `COURSE_LESSON` 材料范围，材料不能放宽课时权限。

`.env.example` 已包含以下默认值；应用从根目录或后端上级目录 `.env` 读取配置，不需要提交本地 `.env`：

```properties
LEARNHUB_AI_MODE=DISABLED
LEARNHUB_KNOWLEDGE_SYNC_ENABLED=false
LEARNHUB_KNOWLEDGE_ALIAS=learnhub-knowledge-search
```

知识表迁移完成后，设置 `LEARNHUB_KNOWLEDGE_SYNC_ENABLED=true`，由正常应用启动时加载。补偿任务每 5 秒处理最多 20 份文档；需要调整时可配置 `learnhub.knowledge.sync-delay`。模型模式可显式设为 `FAKE`，真实模型尚无实现。检索不调用模型，不依赖 Fake 是否开启。

知识别名独立于课程卡片别名，当前 backing index 为 `<alias>-v1`，默认复用 `ik_max_word`/`ik_smart` 中文分词配置。索引失败保留可重试状态并退避；任务完成状态按文档版本更新，旧任务无法把新版本标为已索引。别名缺失时补偿会创建索引并将所有未删除材料重新排队。

## 3. 审校稿件与显式映射

首批来源为 `scripts/course-media/catalog.json`，优先使用场景 narration，缺少时使用 body。转换工具不连接数据库，也不导入数据：

```powershell
# 复制示例到 .local，填入已核验的真实字符串 ID，审校后设置 reviewed=true。
Copy-Item scripts/assistant/mapping.example.json .local/knowledge-mapping.json
node scripts/assistant/prepare-documents.mjs --mapping .local/knowledge-mapping.json > .local/knowledge-documents.json
```

工具按课程 slug 与课时名称唯一匹配；拒绝缺失、重复、未经审校或缺少显式 ID 的映射，不按数组顺序推断数据库关联。输出是导入请求数组，每项单独提交管理员接口。当前没有向现有本机业务库导入演示材料；没有经核验的映射时先使用隔离测试夹具。

## 4. 接口输入与输出

两个接口均沿用 `ApiResponse` 外壳。新增长 ID 在响应中使用字符串。

### 管理员导入

`POST /api/admin/knowledge/documents`，要求 ADMIN：

```json
{
  "sourceKey": "demo:redis-cache:lua-stock",
  "title": "Lua 原子预扣库存",
  "courseId": "填入已核验课程ID",
  "lessonId": "填入已核验课时ID",
  "scenes": [
    {"heading": "原子性", "content": "这里填写经过审校的正文。"}
  ]
}
```

示例 ID 为占位说明，实际请求必须传正整数编号。每份文档关联一个课时。接口检查课程、课时未删除且归属一致；可以为尚未发布的课程准备材料，但用户检索仍遵守课程状态与购买规则。不接收文件路径或远程 URL。

返回 `documentId、sourceVersion、chunkCount、unchanged`。相同 `sourceKey` 和内容重复请求不会重复建块；更新后旧片段立即无法通过当前版本复核，新版完成索引后才可检索。已逻辑删除的来源标识再次导入会明确拒绝。

### 登录用户检索

`POST /api/assistant/retrieve`：

```json
{"courseId":"填入已核验课程ID","question":"为什么使用 Lua？","limit":5}
```

`limit` 可省略，默认 5，范围 1～10；问题最多 500 个 UTF-16 字符。用户 ID 不由请求指定。返回 `requestId、sources、noRelevantMaterials、candidateLimitReached`；每个 source 包含文档/版本/知识块/课程/课时标识、title、heading、chunkOrder、text 和检索 score。

- 未购买用户只能读取已发布课程的预览课时；已购买用户可继续读取下架课程。
- ES 过滤 courseId 与可读 lessonId，仅返回候选来源标识；数据库复核当前材料版本、索引版本、逻辑删除及当前课时权限。
- 退款、下架或课时删除在候选召回后的权限复核中生效；没有可读课时时直接返回空结果。
- 无命中用 `noRelevantMaterials=true` 表示，不宣称模型已判断“证据不足”。ES 故障返回现有 `SEARCH_UNAVAILABLE`/HTTP 503，不包装为空命中。
- 候选上限为 100，达到时返回 `candidateLimitReached=true`。大量删除或过期候选可能使结果不足 limit；本阶段没有搜索结果总数或深分页承诺。

这里的来源是检索证据，并非经过模型生成与校验的回答引用。响应与模型上下文都不包含媒体签名 URL。

## 5. 验证与边界

```powershell
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' test
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' verify
node sql/generate-comments.mjs --check
node --test scripts/assistant/prepare-documents.test.mjs
& ./scripts/assistant/verify-knowledge.ps1
```

专项脚本需要 Docker 可用、本地已有 `mysql:8.4` 镜像，以及可访问且安装 IK 的 Elasticsearch（默认 localhost:9200，可用 `LEARNHUB_KNOWLEDGE_IT_ES_URI` 覆盖）。它只启动本次随机命名及标记的 MySQL 容器，映射随机回环端口，使用临时凭据；测试创建独立数据库与随机知识索引，结束后清理自己的数据库、索引和容器。脚本不启动后端进程，不重启现有服务，不自动拉取镜像。

`docs/assistant-retrieval-evaluation.json` 是 20 条待执行评测规格：8 条可回答、4 条资料不足、4 条权限、4 条恶意或异常。来源映射仍待核验，`NOT_RUN` 不代表质量验收通过。真实模型调用与 MCP 测试执行数均为 0。

第一阶段暂不承诺：真实回答质量、模型引用校验、Token 预算、流式回答、工具调用、任意附件解析和跨用户会话缓存。后续先用已有授权检索接真实 RAG，再增加只读 Agent 和 MCP。

## 6. 本轮实际验收

2026-10-15 已执行以下检查，默认跳过的基础设施用例与专项实际执行用例分别记录：

| 检查 | 实际结果 |
| --- | --- |
| 后端 `mvn test` | 220 项：执行 179、跳过 41，失败及错误均为 0；之后新增的两个基础设施用例在专项中执行 |
| 最终 `mvn verify` | 222 项：执行 179、跳过 43，失败及错误均为 0，打包成功 |
| 隔离 MySQL + ES 专项 | 7 项实际执行，全部通过、无跳过 |
| 稿件转换 Node 测试 | 8 项通过，无跳过 |
| 中文注释静态检查 | 24 表、235 字段通过 |
| 20 条留出评测 | 未执行，材料和课程课时映射仍待核验 |
| 真实模型 / MCP | 执行数均为 0 |

专项覆盖：重复导入与版本失效、错误课时归属、真实中文 IK/BM25 召回及授权课时过滤、索引失败后重试、迁移重复执行与注释/索引约束、材料更新/删除及丢失索引重建，以及两个 RR 事务同时导入同一来源。

测试后核实本次带标签的 MySQL 容器与 `knowledge-it-*` 索引均为 0；未启动常驻后端进程，用户已有服务未重启。测试日志位于忽略目录 `.local/assistant-*.log`，不提交日志和构建产物。

## 7. 本地环境安装记录

2026-10-15，经用户授权，已直接配置 `deploy/docker-compose.yml` 对应的本地环境：

- 复用现有健康的 MySQL、Elasticsearch、Redis、RabbitMQ；ES 已有 8.15.3 的 IK 插件，无需安装额外中间件。
- 在 `learnhub` 库执行 `V20261015__knowledge_documents.sql` 并重复执行验证成功。两张新表合计 24 列，中文注释无缺失，主键、唯一索引、创建和更新时间均已检查。
- 迁移前结构备份保存在被忽略的 `.local/knowledge-setup/schema-before.sql`。迁移后排除两张新表的结构快照与原快照 SHA-256 相同；原课程 24 条、课时 51 条未改变。
- 应用数据库账号已验证可读取两张新表。没有导入演示材料，文档和知识块均为 0 条。
- 已创建 `learnhub-knowledge-search-v1` 和写别名 `learnhub-knowledge-search`，映射与知识 Gateway 一致。本地单节点设置 1 个主分片、0 个副本；健康为 green，真实 IK 分词验证通过，索引中为 0 条文档。
- 本地被 Git 忽略的 `.env` 已设置 `LEARNHUB_KNOWLEDGE_SYNC_ENABLED=true`、`LEARNHUB_KNOWLEDGE_ALIAS=learnhub-knowledge-search`、`LEARNHUB_AI_MODE=DISABLED`。

这次操作未重启现有后端，也未保留临时后端进程；新的 `.env` 配置在下次正常启动后端时加载。材料导入仍须先完成显式课程/课时映射和内容审校。
