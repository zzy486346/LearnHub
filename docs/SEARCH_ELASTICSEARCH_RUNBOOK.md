# 阶段三：Elasticsearch 搜索与索引同步

## 已完成

- 搜索使用 ES 原生 `bool + multi_match + function_score`，文本采用 BM25；标题权重 6、讲师和标签权重 2，描述权重 1。点赞通过 `log1p` 加权，额外分值上限 1，防止热度完全覆盖相关性。
- 标签为 keyword，多标签按全部满足过滤；只返回 PUBLISHED 课程。分页 size 为 1～100，最大深度 10000。
- 联想使用 completion 字段，并以发布状态 context 限制；输入来自标题、讲师、标签，空前缀不查询，重复项去重，最多 20 条。
- MySQL 是事实源；创建、编辑、发布、下架及点赞计数落库和索引任务同事务提交。任务以 course_id 合并、generation 递增，完成与失败更新均通过 generation CAS 防止丢失后续更新。
- 定时同步失败后指数退避重试，最高退避 2560 秒。全量重建 keyset 分页读取 MySQL，构建新索引、刷新后原子切换 alias，旧版本保留。
- 增量同步与重建共用 MySQL advisory lock，重建期间新增任务不会被清空；切换后继续消费任务。业务数据变更到搜索可见存在调度及 ES refresh 延迟。
- ES 不可用、超时或分片失败明确返回 HTTP 503 / SEARCH_UNAVAILABLE，不返回 MySQL/内存拼接的假成功。前端展示错误与重试。

## 本地升级与配置

既有数据库执行 `sql/migrations/V20261007__course_search_sync.sql`，新增带中文注释的 search_index_task 表。当前本机数据库已经执行。全新环境使用 schema.sql。

配置示例在 `.env.example`：

- `LEARNHUB_SEARCH_ALIAS=learnhub-courses-search`
- `LEARNHUB_SEARCH_ANALYZER=standard`
- `LEARNHUB_SEARCH_SEARCH_ANALYZER=standard`
- `LEARNHUB_SEARCH_SYNC_ENABLED=true`
- `LEARNHUB_SEARCH_SYNC_DELAY=5000`
- `LEARNHUB_SEARCH_SYNC_BATCH_SIZE=100`

默认新 alias 避免与旧 concrete index `learnhub-courses` 冲突，不自动删除旧索引。版本索引名为 `<alias>-v1-<随机ID>`，mapping 在 `learnhub-server/src/main/resources/elasticsearch/courses-v1.json`。

当前本地 ES 8.15.3 没有安装 IK，故真实验证采用 standard。需要中文 IK 分词时，在所有 ES 节点安装匹配版本的插件，再配置 analyzer 为 ik_max_word、search analyzer 为 ik_smart，执行全量重建；不要在缺失插件时直接配置 IK。mapping 变更只影响新建版本。

用户自行启动日常后端。本阶段测试通过 MockMvc 与独立测试 alias 验证，没有启动额外监听端口的后端服务。服务运行后，同步任务会在 alias 缺失时自动从 MySQL 初始化；也可由管理员显式重建。

## 管理与故障恢复

以下接口需要 ADMIN 身份，普通用户无权调用：

- `POST /api/admin/search/courses/rebuild`：构建新版本并切换，返回本次索引与同步数量。
- `POST /api/admin/search/courses/sync?limit=100`：处理到期任务，limit 最多 1000。

重建争锁失败返回 409 / SEARCH_SYNC_BUSY。增量任务争锁失败跳过本轮并返回 0，下轮继续；完成数只统计成功删除的所选代次，并发新增代次仍保留待处理。

查询接口：

- `GET /api/search/courses/page?keyword=Java&tags=Java,后端&page=1&size=20`：records、total、current、size。
- `GET /api/search/courses?keyword=Java&page=1&limit=20`：保留列表兼容接口。
- `GET /api/search/courses/suggestions?prefix=Java&limit=8`：联想；旧 /suggest 同样支持。

故障期间业务提交保留同步任务；ES 恢复后定时自动重试，不需要重新修改课程。观察任务积压与最近错误：

```sql
SELECT course_id, generation, attempts, next_retry_at, last_error
FROM search_index_task ORDER BY next_retry_at, id LIMIT 100;
```

失败的重建不会删除当前 alias 指向的索引；alias 切换响应超时时也先核对目标，避免删除已经生效的新版本。旧版本需要人工按 alias 状态与保留策略清理，不能删除当前目标。人工回滚需暂停增量任务、以 ES 原子 aliases API 切回指定旧版本，再重建/补偿并恢复任务；旧版本不是 MySQL 当前状态的替代品。

边界：未新增同义词、高亮、纠错或搜索历史；未安装 IK、未提供旧索引自动清理；任务无限可重试但需监控积压。直接 SQL 修改课程/标签关系不会自动入队，应调用任务服务或全量重建。不要绕开事务写接口。

## 验证

```powershell
D:\Android\apache-maven-3.9.10\bin\mvn.cmd -pl learnhub-server -am "-Dtest=CourseSearchServiceTest,CourseIndexSyncServiceTest,CourseIndexTaskServiceTest,CourseSearchInfrastructureIntegrationTest,CourseIndexSyncInfrastructureIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dlearnhub.it.enabled=true" test
D:\Android\apache-maven-3.9.10\bin\mvn.cmd verify
node sql/generate-comments.mjs --check
cd learnhub-web
npm run test:search
npm run build
```

集成测试连接本地 MySQL/ES，创建随机测试 alias 和课程，只消费本次创建课程的任务，不清空用户数据或正常任务；结束后清理测试数据和索引。

官方资料：[completion suggester](https://www.elastic.co/guide/en/elasticsearch/reference/8.15/search-suggesters.html)、[function_score](https://www.elastic.co/guide/en/elasticsearch/reference/8.15/query-dsl-function-score-query.html)。
