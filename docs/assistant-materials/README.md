# 全课程生成讲义（2026-10-20）

依据本地课程目录，为 24 门课程、51 个未删除课时各编写一份讲义。资料为 AI 生成的教学补充，不是原教师逐字稿，也不声明已对照视频审核。每课时包含概念、机制、示例、边界和自测答案。

数据源为 `catalog-20261020.json`，导入正文分为 `lectures-a.json`、`lectures-b.json`、`lectures-c.json`。`sourceKey` 使用 `generated.course.<课程ID>.lesson.<课时ID>`，与旧 `catalog.*` 来源分开；重复导入相同正文不增加版本，修改正文后由现有服务递增版本。

## 本地导入与验证

在仓库根目录 PowerShell 执行（读取本地 `.env`，仅允许 localhost/127.0.0.1:3306 的 learnhub 库）：

```powershell
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' -pl learnhub-server -am '-Dtest=CatalogMaterialCorpusTest,CatalogMaterialImportIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.assistant.catalog.import=true' test
```

该命令会修改本地知识资料库并同步 Elasticsearch。仅在显式设置导入属性时运行数据库操作；默认 test/verify 不会导入。执行器复用 KnowledgeStore、KnowledgeChunker、KnowledgeIndexSyncService、KnowledgeRetrievalService，不直接拼接数据 INSERT，不启动 HTTP 后端、不调用模型、不读取管理员密码。不要添加覆盖数据库连接的命令行凭据。

导入前校验课程/课时标题与归属、当前目录完整性、来源唯一性和正文长度；每份连续导入两次验证幂等，再验证逐课时 ES 来源命中。有现成可读用户时走授权检索；没有用户获授权的付费课时只验证索引命中和拒绝越权，不创建用户、订单或免费预览。原有来源和正文另做前后快照比对。原始报告在 `.local/catalog-material-import-result.json` 保存，不含用户编号或凭据。

## 使用方法与边界

登录 `/assistant`，新建绑定目标课程的会话后提问；已有会话也会在下一次提问时重新检索。资料随课程/课时权限过滤，导入并不开放付费课时。来源名称带“生成讲义”，保留可追溯身份；当前没有开启课程向量检索，没有向 Mem0 导入课程正文。检索烟测只证明来源命中，不等于模型答案质量验收。

## 实际执行结果

本地显式导入与验证已执行通过，原始结果见 [导入报告](import-result-20261020.json)：

- 新增 51 份生成讲义，正文共 51,188 字符；每份连续导入两次，第二次均保持文档编号和版本不变。最终源文件另与数据库逐份核对业务内容哈希，51/51 一致；报告保留源文件 SHA-256。
- 保留原有 6 份文档及 18 个片段；业务身份、正文、版本快照前后一致。索引同步元数据允许由现有补偿流程更新。
- 导入后共 57 份有效文档、273 个当前版本片段，覆盖 24 门课程；57 份文档均为当前版本已索引。
- 51/51 个按课时限定的真实 ES BM25 查询命中对应讲义；48/48 个有既有可读用户的课时通过真实权限服务检索。
- Spring Boot 3 实战的三个付费课时没有现成授权读者，逐一验证未越权返回目标正文；未修改购买、免费预览或用户数据。Redis Lua 课时有现成可读用户，授权检索已通过。
- 讲义完整性、地址拒绝/调度隔离与实际导入测试共 4 项实际通过，无失败、无跳过。未启动 HTTP 服务、未重启用户后端、未调用供应商模型。来源命中不代表已完成真实生成答案质量验收。
- 根目录 `mvn test`、`mvn verify` 均通过：各 565 项，失败/错误 0、跳过 111、实际执行 454。显式数据库导入测试在日常构建中跳过，其本轮真实执行成绩见上一项。

## 覆盖目录

| 课程编号 | 课程 | 讲义份数 |
| --- | --- | --- |
| 1001 | [Spring Boot 3 实战](courses/course-1001.md) | 4 |
| 1002 | [大模型应用开发入门](courses/course-1002.md) | 4 |
| 12001 | [Java 编程基础入门短课](courses/course-12001.md) | 2 |
| 12002 | [Java 并发编程入门短课](courses/course-12002.md) | 2 |
| 12003 | [JVM 运行原理入门短课](courses/course-12003.md) | 2 |
| 12004 | [MySQL 数据库基础入门短课](courses/course-12004.md) | 2 |
| 12005 | [MySQL 索引优化入门短课](courses/course-12005.md) | 2 |
| 12006 | [Redis 数据结构入门短课](courses/course-12006.md) | 2 |
| 12007 | [Redis 缓存治理入门短课](courses/course-12007.md) | 2 |
| 12008 | [RabbitMQ 消息队列入门短课](courses/course-12008.md) | 2 |
| 12009 | [Docker 容器化入门短课](courses/course-12009.md) | 2 |
| 12010 | [Linux 服务排查入门短课](courses/course-12010.md) | 2 |
| 12011 | [Git 协作基础入门短课](courses/course-12011.md) | 2 |
| 12012 | [Vue 3 组件开发入门短课](courses/course-12012.md) | 2 |
| 12013 | [TypeScript 类型建模入门短课](courses/course-12013.md) | 2 |
| 12014 | [前端网络请求入门短课](courses/course-12014.md) | 2 |
| 12015 | [Elasticsearch 搜索入门短课](courses/course-12015.md) | 2 |
| 12016 | [Python 自动化入门短课](courses/course-12016.md) | 2 |
| 12017 | [提示词工程入门短课](courses/course-12017.md) | 2 |
| 12018 | [RAG 知识问答入门短课](courses/course-12018.md) | 2 |
| 12019 | [AI Agent 工作流入门短课](courses/course-12019.md) | 2 |
| 12020 | [软件测试实践入门短课](courses/course-12020.md) | 2 |
| 12021 | [系统设计基础入门短课](courses/course-12021.md) | 2 |
| 8900835210 | [Redis Lua 原子操作入门](courses/course-8900835210.md) | 1 |
