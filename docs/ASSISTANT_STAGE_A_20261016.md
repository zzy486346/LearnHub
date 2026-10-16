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
| 正式接口导入 | 0；未执行成功 |
| BM25 烟测 | 0；无材料可检索 |
| 开发集/留出评测 | 0 / 0；均未执行 |
| 真实模型/工具调用 | 0 / 0 |

临时后端曾在独立端口 `18081` 健康启动，但本地旧烟测凭据已经失效，登录接口明确返回用户名或密码错误。为保持认证边界，本轮没有直写 `knowledge_document` / `knowledge_chunk`，也没有修改管理员密码。临时后端已按进程归属停止，端口已释放；最终再次核验两张知识表仍为 0 条。

## 有可用本地管理员凭据后的执行方法

在当前 PowerShell 进程设置 `LEARNHUB_STAGE_A_ADMIN_USERNAME`、`LEARNHUB_STAGE_A_ADMIN_PASSWORD`，启动一个启用知识同步的独立测试后端，然后执行：

```powershell
./scripts/assistant/import-stage-a-documents.ps1 -BaseUrl http://127.0.0.1:18081
```

成功标准是输出 `STAGE_A_IMPORT=OK documents=6` 与 `STAGE_A_BM25=OK queries=3 matched=3`。脚本第二次提交同一正文必须返回 `unchanged=true`；任何一份版本变化或 BM25 等待超时都会失败。

20 条留出规格只在 RAG 与权限场景准备完成后运行一次并保留原始结果。日常阈值和提示调试仅使用 8 条开发集，避免看完留出结果后继续调参却仍称独立评测。
