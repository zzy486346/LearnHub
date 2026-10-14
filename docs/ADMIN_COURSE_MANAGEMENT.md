# 管理后台课时维护与课程分页

## 2026-10-14 管理能力更新

后台现已支持编辑和下架课程、编辑章节与课时、上传封面、分类和标签增删改及课程标签绑定。分类选项动态加载，分类和标签 ID 以字符串传输；全部课程仍按每页 10 条展示。

新建课程仅保存为草稿。发布要求分类启用、每章至少一个课时、课时具有正时长及可用视频；已发布课程新增章节或课时须先下架。删除课时如会使已发布课程不完整，事务回滚并提示先下架；历史学习记录继续保留。引用中的分类、标签不能直接删除，有已发布课程的分类不能停用。

已有数据库还须执行 `sql/migrations/V20261012__course_management.sql`；该迁移新增封面关联、标签和绑定时间字段及分类名称唯一索引，不自动合并重复分类。封面保存稳定访问入口，重定向使用 no-store，按请求生成媒体访问地址。

最新隔离脚本用 PowerShell 7 执行：`pwsh -NoProfile -File scripts/admin/verify-course-management.ps1`。已通过新安装与升级库的重复迁移、字段属性和旧数据保真检查，以及 6 项真实 MySQL 集成测试。最新全量检查、数据库升级和剩余边界见[平台剩余问题验收记录](PLATFORM_REMAINING_FIXES_20261014.md)。以下原验收数字为 2026-10-11 的历史记录。

## 功能与接口

- 管理后台“全部课程”按服务端分页展示，每页固定 10 门，按课程 ID 倒序排列；返回总量，支持上一页、页码和下一页。新建课程后回到第一页并选中新课程，详情加载失败时不会继续显示上一页旧课程的编辑器。
- 在课程章节中点击“添加课时”，填写标题、时长、排序和试看状态；添加后刷新课程目录。默认排序取章节现有课时最大排序值加一，提交中阻止重复操作。
- 每条课时提供“删除”按钮，确认后才调用接口，取消不发送请求。删除失败保留课时并显示服务端反馈，删除期间禁止重复提交及冲突操作。
- `GET /api/admin/courses?page=1` 返回 `ApiResponse<Page<Course>>`，分页字段为 `records / total / current / size`，固定 `size=10`；不再返回全量数组。页码小于 1 时按第一页处理。
- `POST /api/admin/chapters/{chapterId}/lessons` 沿用既有创建接口，校验所属章节与课程、标题去除空白后非空。
- `DELETE /api/admin/lessons/{lessonId}` 返回 `ApiResponse<Void>`。不存在或已删除的课时返回业务冲突（409）。接口仍要求管理员权限，普通用户不可操作。

## 删除语义与数据库升级

课时删除将 `course_lesson.deleted` 设为 1，不删除媒体文件或历史学习记录。管理员、学员的课程目录和课时访问均排除该课时，学习列表、最近学习和完成课时统计也不再包含它；历史数据库行仍保留。

新环境使用最新 `sql/schema.sql`。已有环境须先执行 `sql/migrations/V20261011__course_lesson_soft_delete.sql` 再加载新后端代码。迁移仅新增 `deleted TINYINT NOT NULL DEFAULT 0` 及中文 COMMENT，可重复执行，不改变原有字段属性、索引或业务数据。

本机 Compose 环境执行方式：

```powershell
Set-Location D:\Project\myproject\LearnHub
Get-Content -LiteralPath sql\migrations\V20261011__course_lesson_soft_delete.sql -Raw |
  docker compose -f deploy\docker-compose.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" --default-character-set=utf8mb4'
```

2026-10-11 已升级当前本机 `learnhub` 数据库。迁移前后均为 51 条课时、14 条学习记录；原有课时字段属性、字段和表注释、索引数量、原有课时数据校验值均一致。新增字段为 `tinyint / NOT NULL / DEFAULT 0`，注释为“逻辑删除标记：0正常，1已删除”，全部既有课时保持正常状态。未重启用户日常后端。

## 验证与复现

真实 MySQL 验收（Docker 已运行，本地具有 `mysql:8.4` 镜像）：

```powershell
Set-Location D:\Project\myproject\LearnHub
& .\scripts\admin\verify-course-management.ps1
```

脚本使用随机名称、归属标签、随机 loopback 端口和 tmpfs 的独立 MySQL 容器。新安装库直接使用正式 schema；升级库仅在临时副本中移除本次新增课时列，再执行正式迁移两次，覆盖实际 ADD 路径与幂等性，不修改原始 schema 或已有业务库。通过十六进制比较验证新安装列的中文注释，避免终端字符集影响。

`AdminCourseInfrastructureIntegrationTest` 必须通过 `learnhub.admin.course.it.enabled=true` 显式启用，默认跳过。脚本传入隔离端口并校验本次报告至少 3 项实际执行，不能用跳过测试的 Maven 成功代替验收。测试覆盖：真实 SQL 每页 10 条与 ID 倒序；创建、逻辑删除、目录与访问过滤、完成统计以及历史记录保留；缺失父课程和空白标题校验。测试按自有 ID 清理夹具并关闭数据库上下文，脚本在 `finally` 中验证归属标签后删除本次容器和临时文件，清理故障会报错。

本次交付结果：

- 真实 MySQL 集成：3 项执行通过，无跳过、失败或错误。
- Maven `test`、`verify`：均为 `BUILD SUCCESS`，默认 102 项中 82 项执行通过、20 项 opt-in 测试跳过；上述新增 3 项已单独开启执行，其余 opt-in 测试本轮未复跑。
- 前端全部回归：52 项通过，其中后台管理 13 项；`npm run build` 的 Vue 类型检查和生产构建通过，保留既有大体积 chunk 警告。
- 数据库中文注释检查：22 张表、197 个字段通过；PowerShell 语法与 `git diff --check` 通过。
- 本次测试 JVM 和隔离容器、临时目录均已清理，无测试后端服务驻留。未提交密钥、本地配置或构建产物。

代码位于 `feature/admin-lessons-pagination`，按中文 Conventional Commits 提交，不自动合并或推送。
