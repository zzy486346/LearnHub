# 学习助理阶段 A：材料基线与评测拆分

## 已完成

- 2026-10-16 从本地 `learnhub` MySQL 事实表只读核验 3 门课程、6 个课时；使用 UTF-8 标题十六进制复核终端乱码，未按数组位置推断 ID。
- 逐场景审校 `catalog.json` 中 Java 并发、Redis 缓存治理、Elasticsearch 搜索讲解稿，共 6 份文档、18 个场景。显式映射见 `scripts/assistant/stage-a-mapping.json`。
- 新建 8 条开发集，用于后续调试召回与拒答；原 20 条继续全部作为留出规格，并修正为本次真实材料的来源、问题和预期术语。
- 新增正式管理员接口导入脚本。脚本只从进程环境读取管理员凭据，先导入，再重复调用验证幂等，最后等待 3 条 BM25 查询返回来源；不打印密码或令牌。

## 本轮真实环境结果

| 项目 | 结果 |
| --- | --- |
| MySQL | 可达；目标课程/课时唯一归属核验通过 |
| Elasticsearch | 可达；单节点 `yellow`，知识别名指向 `learnhub-knowledge-search-v1` |
| 导入前文档/块 | 0 / 0 |
| `KnowledgeStore` 受控导入 | 6 份文档、18 个知识块；重复导入幂等通过 |
| 索引同步 | 6 份当前版本均为 `INDEXED`，知识别名中 18 个块 |
| BM25 开发集 | 实际执行 8 条；6 条可回答查询命中预期来源，1 条跨课程查询无禁用来源泄漏，1 条资料不足查询记录到 2 个弱命中 |
| 20 条留出评测 | 0；保持未执行，留待 RAG、权限夹具及引用校验完成后独立验收 |
| 真实模型/工具调用 | 0 / 0 |

旧烟测凭据已经失效，因此没有修改管理员密码或增加生产绕过接口。真实导入改由默认跳过的 `StageAMaterialImportIntegrationTest` 完成：只有显式设置 `-Dlearnhub.assistant.stage-a.import=true` 才加载本地主配置，逐份复核真实课程、章节和课时归属，再调用既有 `KnowledgeStore` 与 `KnowledgeIndexSyncService`。最终只读核验 MySQL 为 6 份当前文档、18 个当前块，6 份 `indexed_version=source_version`；Elasticsearch 知识别名为 18 个块。

开发集证明 BM25 可以找回六条预期课程资料，也证明 `courseId + 可读课时` 过滤没有把 Redis 正文泄漏给 Java 课程查询。`DEV-007` 的资料不足问题仍召回两个同课程弱相关块，这是原始 BM25 的真实现象；它不算拒答通过，后续 RAG 必须用开发集校准证据不足判断，并在留出集上独立验收。

## 有可用本地管理员凭据后的执行方法

需要重复验证正式 HTTP 管理员路径时，可在当前 PowerShell 进程设置 `LEARNHUB_STAGE_A_ADMIN_USERNAME`、`LEARNHUB_STAGE_A_ADMIN_PASSWORD`，启动一个启用知识同步的独立测试后端，然后执行：

```powershell
./scripts/assistant/import-stage-a-documents.ps1 -BaseUrl http://127.0.0.1:18081
```

成功标准是输出 `STAGE_A_IMPORT=OK documents=6` 与 `STAGE_A_BM25=OK queries=3 matched=3`。脚本第二次提交同一正文必须返回 `unchanged=true`；任何一份版本变化或 BM25 等待超时都会失败。

无需 HTTP 凭据的本地运维验收使用同一管理服务，不直接写知识表：

```powershell
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' -q -pl learnhub-server `
  '-Dtest=StageAMaterialImportIntegrationTest' `
  '-Dlearnhub.assistant.stage-a.import=true' test
```

20 条留出规格只在 RAG 与权限场景准备完成后运行一次并保留原始结果。日常阈值和提示调试仅使用 8 条开发集，避免看完留出结果后继续调参却仍称独立评测。
