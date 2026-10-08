# LearnHub：Elasticsearch 搜索步骤与代码实现

> 本文按 2026-10-08 的项目代码说明实际行为，覆盖课程进入索引、用户搜索、联想与故障恢复。示例课程 ID 需要替换为实际值。本文不是部署手册；插件安装和运维步骤见 [搜索运行手册](D:/Project/myproject/LearnHub/docs/SEARCH_ELASTICSEARCH_RUNBOOK.md)。

## 1. Elasticsearch 在项目中的定位

MySQL 是课程业务数据的事实源，Elasticsearch（ES）是可重建的搜索投影。两者不是同时提交的跨库事务。

- MySQL 保存课程、章节、课时、标签关系及数据库点赞计数。
- 后端通过持久化同步任务，把课程检索需要的信息写入 ES。
- ES 负责文本分析、倒排索引、多字段匹配、条件过滤、相关性评分、热度加权、排序分页和输入联想。
- ES 不直接监听 MySQL，不保存视频文件，也不负责课程购买和发布的业务事务。
- Kibana 是查看 ES 数据和执行查询的界面，不参与课程同步或用户搜索请求的处理。

整个功能分为两个独立过程：先建立并维护索引，再利用索引执行查询。

## 2. 实现入口速查

| 代码位置 | 对应职责 |
| --- | --- |
| [CourseListView.vue](D:/Project/myproject/LearnHub/learnhub-web/src/views/CourseListView.vue) | 收集关键词、标签、分页参数，调用搜索和联想接口，展示结果与错误 |
| [api/index.ts](D:/Project/myproject/LearnHub/learnhub-web/src/api/index.ts) | `courseApi.searchPage()`、`courseApi.suggestions()` 封装 HTTP 请求 |
| [CourseSearchController.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseSearchController.java) | 解析搜索、分页、联想请求参数 |
| [CourseSearchService.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseSearchService.java) | 参数校验、DSL 组装、搜索响应转换、联想结果提取 |
| [CourseSearchGateway.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseSearchGateway.java) | 使用 RestClient 调用 ES，创建索引、写文档、刷新与切换别名 |
| [courses-v1.json](D:/Project/myproject/LearnHub/learnhub-server/src/main/resources/elasticsearch/courses-v1.json) | 字段类型、IK analyzer、completion context 等版本化 mapping |
| [AdminCourseService.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/course/service/AdminCourseService.java) | 课程创建、修改、发布、下架时在事务内生成同步任务 |
| [LikePersistenceService.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/interaction/like/LikePersistenceService.java) | 课程点赞计数落库或对账后，在事务内提交索引任务 |
| [CourseIndexTaskService.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseIndexTaskService.java) | 封装单课程及批量入队 |
| [CourseIndexTaskMapper.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseIndexTaskMapper.java) | 任务合并、查询到期任务、代次校验删除和失败更新 |
| [CourseIndexSyncJob.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseIndexSyncJob.java) | 定时触发同步 |
| [CourseIndexSyncService.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseIndexSyncService.java) | 增量同步、失败退避、互斥、首次初始化和全量重建 |
| [CourseIndexSourceMapper.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseIndexSourceMapper.java) | 按课程 ID 或 ID 游标从 MySQL 获取索引事实数据及真实标签 |
| [CourseSearchAdminController.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/search/CourseSearchAdminController.java) | 管理员主动同步与重建接口 |
| [GlobalExceptionHandler.java](D:/Project/myproject/LearnHub/learnhub-server/src/main/java/com/learnhub/infrastructure/GlobalExceptionHandler.java) | 检索不可用时返回 HTTP 503 / SEARCH_UNAVAILABLE |

## 3. 搜索前：课程如何建立成 ES 文档

### 3.1 保存课程与生成任务

`AdminCourseService.createCourse()`、`updateCourse()`、`publishCourse()`、`offlineCourse()` 均在数据库事务内调用 `indexTaskService.enqueue(courseId)`。

课程写入与任务写入使用同一 MySQL 事务：提交时两者一起生效，事务回滚时两者一起撤销。ES 写入不在这个事务中，ES 暂时不可用不会阻止课程保存。

`LikePersistenceService` 在课程点赞计数批量落库或对账修复后同样入队。搜索中的点赞热度来自数据库已落库计数，不保证与 Redis 实时数立即一致。

`CourseIndexTaskMapper.enqueue()` 使用 `course_id` 唯一约束合并任务：

- 第一次变更：插入任务，`generation=1`。
- 后续变更：`generation` 递增，重置失败次数、错误与重试时间。
- 多次变更不必累积多个重复任务，同步时读取课程最新状态。

直接通过 SQL 修改课程或标签关系不会自动触发该应用层入队逻辑，需要显式生成任务或全量重建。

### 3.2 定时调度不是全量扫描课程

`CourseIndexSyncJob.sync()` 使用：

```java
@Scheduled(fixedDelayString = "${learnhub.search.sync-delay:5000}")
```

这是上一轮执行结束后再等待默认 5 秒，不是严格每隔 5 秒启动一次，也不表示搜索会在 5 秒内必然可见。

正常一轮调用 `CourseIndexSyncService.syncDue(batchSize)`，默认最多选取 100 条到期任务，流程如下：

1. 尝试获取 MySQL advisory lock，未获得则跳过本轮。
2. 检查搜索 alias；不存在时先执行全量初始化。
3. 调用 `CourseIndexTaskMapper.selectDue(limit)` 选取到期任务。
4. 没有任务且 alias 已存在时，不查询课程表，本轮结束。
5. 有任务时按任务的 `course_id` 查询对应课程，不读取所有课程。

任务选择 SQL 的核心是：

```sql
SELECT id, course_id, generation, attempts, next_retry_at, last_error,
       created_at, updated_at
FROM search_index_task
WHERE next_retry_at <= CURRENT_TIMESTAMP(3)
ORDER BY next_retry_at, id
LIMIT #{limit};
```

这是 Mapper 参数化 SQL，`#{limit}` 不是可以直接在 MySQL 控制台使用的语法。

### 3.3 获取最新课程并写入或删除文档

`syncOne()` 调用 `CourseIndexSourceMapper.selectByCourseId(courseId)`，读取课程字段，并通过 `course_tag`、`tag` 和 `JSON_ARRAYAGG` 聚合标签。

- `status=PUBLISHED` 且未删除：转换为 `CourseSearchDocument` 后调用 `gateway.upsert()`。
- 草稿、下架、逻辑删除、物理不存在：调用 `gateway.delete()`。

Gateway 以课程 ID 作为 ES `_id` 写入：

```http
PUT /learnhub-courses-search/_doc/1002
```

相同 `_id` 再写入会替换对应文档，不会因课程编辑产生多份同 ID 文档。MySQL 的 `instructor` 在 ES 中映射为 `instructorName`，接口返回时又转换为 `instructor`。

### 3.4 ES 分词并建立检索结构

文档核心字段的 mapping：

| 字段 | 类型及用途 |
| --- | --- |
| `id` | keyword，文档业务 ID 与稳定排序字段 |
| `title` | text，索引分词 ik_max_word、查询分词 ik_smart；子字段 title.exact 为完整标题 keyword |
| `description`、`instructorName` | text，同样使用 IK 索引与查询分词 |
| `tags`、`status` | keyword，按完整值过滤，不拆词 |
| `likeCount` | long，参与业务热度加权 |
| `publishedAt` | date，保存发布时间；当前查询未用它做排序 |
| `coverUrl` | keyword 且不建立检索索引，供卡片展示 |
| `suggest` | completion，以 status 作为 publication context |

ES 将文本分析成词项，建立倒排索引，记录词项与匹配文档之间的关系。原文保存在 `_source` 中；一门课程仍是一份文档，不是每个分词各产生一份文档。

查询时利用词项检索结构找到候选课程，不需要让 Java 拉取全部课程再逐条匹配。

### 3.5 成功删除任务，失败保留重试

同步成功调用 `deleteIfGeneration(courseId, generation)`。必须满足课程 ID 与所选代次都一致才删除：同步期间课程再变更产生新代次时，旧任务不能把新任务删除。

失败调用 `failIfGeneration()`，同样按代次校验，增加失败次数、保存错误并推迟重试。当前算法的延迟为 5、10、20 秒等指数增长，指数截断后最高实际延迟为 2560 秒；到期不意味着立即执行，还受调度、积压及锁占用影响。

`search_index_task` 是待处理/重试任务表，不是成功同步历史表。表为空可能是任务已处理，也可能根本没有入队；必须查询 ES 文档确认结果。

ES 成功接收写入与搜索可见不是同一时刻，增量同步还需等待正常 refresh。代码没有对每次增量写入强制 refresh。

## 4. 用户正式搜索的完整步骤

### 4.1 前端收集条件并选择接口

`CourseListView.load()` 根据条件分流：

- 无关键词且无标签：`courseApi.list()`，使用普通 MySQL 课程分页。
- 有关键词或标签：`courseApi.searchPage()`，使用 ES 搜索分页。

搜索请求示例：

```http
GET /api/search/courses/page?keyword=大模型&page=1&size=12
```

页面把所选标签拼成逗号分隔参数；提交新搜索或更改标签时将页码重置为 1。请求代次校验避免早先的慢响应覆盖最新搜索结果。

### 4.2 Controller 转交与 Service 校验

`CourseSearchController.page()` 转交 `CourseSearchService.searchPage()`。后者要求页码至少 1、页大小 1～100，且 `page × size <= 10000`。

`query()` 将关键词 trim，限制最多 200 字符；最多筛选 20 个标签，每个标签最多 64 字符且不能为空。超出规则时返回业务错误，不执行该 ES 查询。

### 4.3 创建原生 ES DSL

以下例子对应实际 `query()` 结构，表示检索“大模型”并要求 AI 标签：

```json
{
  "from": 0,
  "size": 12,
  "track_total_hits": true,
  "query": {
    "function_score": {
      "query": {
        "bool": {
          "must": [
            {
              "multi_match": {
                "query": "大模型",
                "fields": ["title^6", "instructorName^2", "tags^2", "description"],
                "type": "best_fields"
              }
            }
          ],
          "filter": [
            { "term": { "status": "PUBLISHED" } },
            { "terms": { "tags": ["AI"] } }
          ]
        }
      },
      "field_value_factor": {
        "field": "likeCount",
        "factor": 0.2,
        "modifier": "log1p",
        "missing": 0
      },
      "max_boost": 1.0,
      "boost_mode": "sum"
    }
  },
  "sort": [{ "_score": "desc" }, { "id": "asc" }]
}
```

关键词为空但选择了标签时，文本条件改为 `match_all`，仍执行状态和标签过滤。多标签各生成一个独立的 `terms` filter，多个条件之间为 AND，必须全部满足。

### 4.4 通过 alias 向 ES 发起请求

`CourseSearchGateway.search()` 使用 RestClient 发送：

```http
POST /learnhub-courses-search/_search
```

后端始终访问稳定 alias，而不是在检索代码中固定某个版本索引名。alias 指向当前生效的版本索引。

### 4.5 ES 分析查询并匹配候选课程

文本字段根据 mapping 使用 `ik_smart` 分析查询。`multi_match` 在标题、讲师、标签和描述上查找匹配；keyword 标签字段本身不采用 IK 拆词。

当前 `best_fields` 主要取匹配效果最好的字段分数。没有设置 `operator: AND` 或 `minimum_should_match`，因此文本查询默认是 OR：满足任意词项即可成为候选。

例如此前实际分析“大模型应用开发入门”得到“大、模型、应用、开发、入门”；Spring Boot 课程描述中的“应用”就足以匹配。搜索“大模型”则不会通过“应用”这个词命中它。分词结果也会受词典及配置影响。

外层 `bool.must` 要求满足整个 `multi_match` 查询，不意味着该查询中的每个词项都必须匹配。设置更严格的 AND 或最低匹配比例是可以选择的优化，但不是当前已实现的行为。

### 4.6 ES 过滤与评分

发布状态及标签在 filter 上下文中过滤，不增加文本相关性分数。文本相关性默认由 BM25 计算，考虑词频、词的普遍程度和字段长度等因素。

字段权重为标题 6、讲师 2、标签 2、描述 1，表达“更重视标题匹配”，不是保证任何标题命中都绝对排在描述命中之前。

随后 function_score 将点赞数乘以 0.2，经 log1p 变换，再将业务加分限制为最多 1 分，通过 boost_mode=sum 加到基础文本分数上。完全不满足基础匹配条件的课程不会仅靠点赞进入结果。

相关性低只意味着排序靠后，不代表自动淘汰。当前没有设置最低分数阈值，因此只命中一个通用词的课程仍可能返回。

### 4.7 ES 排序、分页，后端转换响应

ES 按 `_score` 降序、分数相同时按 keyword 类型 `id` 升序排序。这里的 ID 排序是字符串排序，不是数值大小排序。

分页使用 `from=(page-1)×size` 与 `size`；例如第 2 页每页 12 条，from 为 12。`track_total_hits=true` 获取匹配总数。

`searchPage()` 提取 hits 中的 `_source` 和 `_score`，生成 `SearchResult` 与 `SearchPage(records,total,current,size)`，Controller 再使用统一 ApiResponse 包装。

前端显示课程卡片、总数和分页。当前正式搜索链路不额外回查 MySQL 拼接结果，也不在 JVM 中读取全部 ES 文档后过滤排序。

## 5. 输入联想：与正式搜索独立

`CourseListView.fetchSuggestions()` 调用：

```http
GET /api/search/courses/suggestions?prefix=大模&limit=8
```

`CourseSearchService.suggest()` 使用 ES completion suggester，而不是 Java startsWith 或正式搜索结果截取：

1. 规范化前缀，空前缀直接返回空列表，不查询 ES。
2. 前缀最多 100 字符，非空前缀的返回数量必须为 1～20；前端通常请求 8 条。
3. 查询 `suggest` 字段，设置 `skip_duplicates=true`。
4. 使用 publication=PUBLISHED context 限制发布状态。
5. 提取建议文本，再去重并限制数量。

`CourseSearchGateway.index()` 为标题、讲师和标签生成 suggestion input，截取到最多 100 字符。该字段是专门为联想构建的检索结构，不是把所有文档拉回客户端筛选。

用户点击建议后，页面填入建议文本，并发起正式搜索。联想失败只清空建议，正式搜索失败则展示错误与重试。

## 6. 初始化、全量重建与失败边界

### 6.1 何时全量读课程

只有 alias 不存在时自动初始化，或管理员调用 `POST /api/admin/search/courses/rebuild` 时主动全量重建。

`rebuildLocked()` 用 `selectPageAfterId(afterId,batchSize)` 按 ID 游标逐页读课程，再由 `isSearchable()` 筛选已发布且未删除课程写入 ES。数据库读取的页包含其他状态课程，但不会将它们建立成可搜索文档；整个过程不一次性加载全部课程。

### 6.2 新索引与原子切换

1. `createVersionedIndex()` 读取 courses-v1.json，应用当前 analyzer 配置，创建 `<alias>-v1-<随机ID>`。
2. 将 MySQL 当前可搜索课程写入新版本。
3. 显式 refresh 新版本，确保切换前可以搜索。
4. `swapAlias()` 用一次 `_aliases` 请求移除旧绑定、添加新绑定。
5. 管理员重建在切换后继续处理到期任务，保留重建期间产生的后续变更。

正常重建不删除旧版本。失败尝试清理本次新索引，但 `deleteIndex()` 会先确认该索引不是 alias 当前目标，防止切换已生效而响应超时时误删当前索引。

增量同步和重建使用同一个基于 alias 的 MySQL GET_LOCK。增量争锁失败跳过本轮，管理员重建争锁失败返回 SEARCH_SYNC_BUSY。这个锁不禁止用户修改课程，课程修改会继续生成待同步任务。

安装 IK、修改配置都不会重写已有索引的分词结果；需要加载新配置后全量重建。配置位置见 [application.yml](D:/Project/myproject/LearnHub/learnhub-server/src/main/resources/application.yml)。

### 6.3 ES 不可用时不伪装成功

Gateway 检查 timed_out 与分片失败，也会把 ES 请求异常转换为 SearchUnavailableException；GlobalExceptionHandler 返回 HTTP 503 / SEARCH_UNAVAILABLE。

正式搜索不静默回退到 MySQL、JVM 缓存或硬编码课程。索引写入故障则保留同步任务重试。增量同步属于最终一致，不承诺课程刚提交就立即可以搜到。

## 7. 用 Kibana 验证每个步骤

以下均为读取/分析请求，不会发布课程或切换索引。

### 7.1 查看 alias 和真实 mapping

```http
GET _cat/aliases/learnhub-courses-search?v

GET learnhub-courses-search/_mapping
```

确认 alias 绑定版本索引；检查 title 的 analyzer=ik_max_word、search_analyzer=ik_smart，以及 suggest 的 completion 类型。

### 7.2 确认课程文档已同步

```http
GET learnhub-courses-search/_search
{
  "query": {
    "term": {
      "title.exact": "Redis Lua 原子操作入门"
    }
  }
}
```

检查 hits.total.value、`_id` 与 `_source`。根据查到的实际 `_id` 还可以执行 `GET learnhub-courses-search/_doc/<课程ID>`，该写法须先替换占位符。

### 7.3 分别观察索引与查询分词

```http
POST learnhub-courses-search/_analyze
{
  "field": "title",
  "text": "大模型应用开发入门"
}

POST learnhub-courses-search/_analyze
{
  "analyzer": "ik_smart",
  "text": "大模型应用开发入门"
}
```

第一个请求根据 title 的索引 analyzer 分析，第二个显式观察 ik_smart。返回 tokens 中的 token、position、start_offset、end_offset 分别表示词项、位置和原文偏移。

### 7.4 查看课程字段词项

```http
GET learnhub-courses-search/_termvectors/1002
{
  "fields": ["title", "description"],
  "positions": true,
  "offsets": true,
  "term_statistics": false,
  "field_statistics": false
}
```

1002 是示例 ID。当前 mapping 没有要求存储 term vectors，因此 ES 可按字段配置动态生成；这个接口不是直接导出整个倒排索引。

### 7.5 解释为什么命中某门课程

```http
GET learnhub-courses-search/_explain/1001
{
  "query": {
    "multi_match": {
      "query": "大模型应用开发入门",
      "fields": ["title^6", "instructorName^2", "tags^2", "description"],
      "type": "best_fields"
    }
  }
}
```

1001 是示例 ID。该示例只解释文本匹配，不包含状态、标签过滤及点赞加权；需要解释完整最终得分时，使用第 4.3 节的完整 query 对象。观察 matched 与 explanation.details，可定位实际命中的字段和词项。

## 8. 测试对应关系与当前限制

| 测试 | 覆盖内容 |
| --- | --- |
| CourseSearchServiceTest | DSL、参数范围、分页响应、联想与显式失败 |
| CourseSearchInfrastructureIntegrationTest | 真实 IK 分词、中文搜索和联想、BM25/热度排序、标签与状态过滤、版本索引切换 |
| CourseIndexTaskServiceTest / CourseIndexSyncServiceTest | 入队、所选代次完成、删除与锁冲突等逻辑 |
| CourseIndexSyncInfrastructureIntegrationTest | MySQL 真实任务、课程发布状态、标签与计数同步、失败重试、新代次不丢失、重建失败保护及权限 |
| learnhub-web/scripts/course-search.test.mjs | 普通列表与搜索分流、过期响应保护、联想和错误重试 |

以上为现有测试用途说明，本文编写只检查代码、文档链接与格式，不意味着再次执行所有测试。

当前限制：文本 OR 匹配可能召回弱相关课程；缺少同义词、纠错、高亮、搜索历史和自动旧索引清理；需监控同步积压；直接 SQL 修改不会自动入队；字段或 analyzer 变更需要重建，不是修改配置后立即改变旧索引。

## 9. 官方参考

- [ES 8.15 multi_match](https://www.elastic.co/guide/en/elasticsearch/reference/8.15/query-dsl-multi-match-query.html)
- [ES 8.15 function_score](https://www.elastic.co/guide/en/elasticsearch/reference/8.15/query-dsl-function-score-query.html)
- [ES 8.15 BM25 / similarity](https://www.elastic.co/guide/en/elasticsearch/reference/8.15/index-modules-similarity.html)
- [ES 8.15 completion suggester](https://www.elastic.co/guide/en/elasticsearch/reference/8.15/search-suggesters.html)
- [IK 维护者说明](https://github.com/infinilabs/analysis-ik)
