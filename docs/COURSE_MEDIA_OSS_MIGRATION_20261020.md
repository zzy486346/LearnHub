# 课程封面与视频 OSS 迁移记录

日期：2026-10-20。用户授权将本地演示课程媒体上传现有 OSS，再删除 `course-media`。

## 完成结果

- 原本地挂载目录已为空，按仓库 `scripts/course-media/catalog.json` 的原讲解脚本重新生成 21 门演示短课，未使用其他课程的视频替代。
- 42 个视频共 117,209,095 字节，实际总时长 6,868 秒；全部通过完整解码、文件去重和画面变化检查。
- 21 个封面从同一目录数据生成 PNG，以兼容现有课程封面上传接口；抽查了实际图片。封面与视频合计 117,921,619 字节。
- 首次实际上传 63 个对象，并通过现有 `AdminCourseService`、`MediaAssetService` 绑定原课程和课时；封面指向现有课程封面 API，视频绑定媒体资源 ID，访问时生成 URL，不把临时签名 URL 持久化到课程表。
- 每个对象均做 OSS HEAD 大小/类型检查及 HTTP 读取检查；视频要求 Range 请求返回 206。首次和幂等复跑各 63 次 HEAD、63 次 HTTP 检查全部通过。
- 重复迁移新增上传数为 0。原有三门课程和九个视频绑定未变；未修改购买权限、用户或学习进度。
- 当前数据库中封面和课时视频的 `8091` 本地地址引用均为 0。确认引用归零后停止并移除 `deploy-course-media-1`，没有删除任何数据库卷。
- 已删除 Compose 的 `course-media` 服务及专用 Nginx 配置，删除 `.env.example` 和本地 `.env` 中过时的媒体目录变量。完整 Compose 配置解析通过，不再包含该服务。

首次与幂等复跑摘要归档在 [机器可读回执](course-media-oss-migration-20261020.json)。本地回退信息保存在 Git 忽略的 `.local/course-media-migration-before.json`，重新生成的素材保存在 `.local/course-media-recovery`，均不提交。旧 URL 所指文件已丢失，回退旧 URL 本身不能恢复播放。

## 可复跑维护流程

只面向本机开发数据库及已有演示课程，不用于外部生产库。先准备本地 `.env` 的现有 OSS 配置，不输出密钥或签名 URL。

```powershell
# 仓库根目录；全部生成素材均受 .local 的 Git 忽略规则保护。
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/course-media/narrate.ps1 -OutputDir "$PWD/.local/course-media-recovery"
python scripts/course-media/render.py --output .local/course-media-recovery --workers 3
python scripts/course-media/cover_png.py --output .local/course-media-recovery
python scripts/course-media/verify.py --output .local/course-media-recovery

# 明确开启才会访问真实 OSS 并修改课程媒体绑定；普通构建自动跳过。
& 'D:\Android\apache-maven-3.9.10\bin\mvn.cmd' -pl learnhub-server -am '-Dtest=LocalCourseMediaOssMigrationIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dlearnhub.media.local.migrate=true' test
```

维护上下文使用既有本机 MySQL/ES/Redis 守卫，禁用全部定时任务及两个业务启动迁移器，不启动 HTTP 后端，不调用生成模型或 Mem0。执行前核对 21 门课程标题、42 个课时标题、全部文件与精确旧 URL；已有绑定按 READY、业务类型和业务 ID 验证，不重复上传。上传复用现有事务方法，视频时长依据生成清单核对更新；最后处理课程搜索同步任务。首次发现并修复了维护查询错误引用不存在的章节逻辑删除字段，修复前没有发生媒体上传或绑定修改。

本次没有重启用户后端。已有后端使用原有 OSS 访问逻辑即可读取新绑定，刷新课程页面验收。旧 `sql/demo-courses.sql` 及文档中的本地 URL 属于历史首次演示导入记录，不应覆盖当前 OSS 绑定。

## 验证记录

- 原媒体目录相关 Node 测试 4 项通过；媒体与课程管理专项回归通过；真实 OSS 维护测试首次上传及幂等复跑均通过。
- 最终根目录 `mvn test`、`mvn verify` 均退出 0：各统计 580 项，实际执行 467 项，按条件跳过 113 项，零失败、零错误。真实 OSS 维护测试在普通构建中跳过，上述两轮真实操作使用显式开关单独执行。
- 首次全量回归曾遇到既有 `Mem0HttpClientTest` 的时限错误码断言波动；单项复跑及最终两轮全量回归均通过，未修改 Mem0 生产逻辑。自动审批额度曾阻止一次测试命令启动，恢复后已完成全部上述测试。
- 完整 Compose 配置与 `git diff --check` 验证通过。无测试 HTTP 后端遗留、无新增日志文件；媒体源文件、凭据和回退信息未提交。
