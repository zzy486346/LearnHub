# 数据库结构与中文注释

- `schema.sql`：新环境建表基线，全部业务表及字段使用中文 `COMMENT`。
- `schema-comments.json`：中文注释映射，包含状态含义、金额及时间单位。
- `migrations/V20261007__chinese_schema_comments.sql`：为既有 `learnhub` 数据库补齐注释，保留已有中文注释，可重复执行。

## 既有数据库补注释

执行前核对目标为本项目的 MySQL 8.4 数据库，并备份表结构。迁移读取真实字段定义，保留类型、默认值、可空性、字符集、排序规则、自增和自动更新时间；不修改业务数据。

本地 Docker 环境执行：

```powershell
docker cp sql/migrations/V20261007__chinese_schema_comments.sql deploy-mysql-1:/tmp/learnhub_V20261007_comments.sql
docker exec deploy-mysql-1 sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 learnhub -e "SOURCE /tmp/learnhub_V20261007_comments.sql;"'
```

脚本末尾输出缺失中文表注释和字段注释的数量，两项均应为 `0`。若出现未映射字段或生成列，迁移在变更前报错，应先核对结构并扩展映射。

## 注释维护与验证

在项目根目录执行：

```powershell
node sql/generate-comments.mjs --check
node sql/generate-comments.mjs --snapshot
node sql/generate-comments.mjs --validate-schema
```

- `--check`：核对建表基线与中文注释映射。
- `--snapshot`：读取本地数据库的字段属性、索引、表属性、准确行数和数据校验和；对迁移前后结果进行比较，可确认注释之外没有变更。
- `--validate-schema`：在两个随机命名的临时数据库中执行已提交及当前建表基线，验证字段属性一致及中文注释完整，结束时清理本次验证数据库。
- `--patch`：根据映射输出 `apply_patch` 格式的建表及注释迁移补丁，不直接改写源文件。

新增字段时同步维护映射、建表基线和相应增量迁移。既有 `ADD COLUMN` / `MODIFY COLUMN` 补丁也必须带中文注释，避免后续升级清空注释。验证不需要启动后端服务。
