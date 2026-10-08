# 演示课程与媒体说明

## 内容定位

本项目附带 21 门本地演示短课，每门课程包含 2 个可直接预览的动画讲解课时。课程脚本由 AI 辅助编写，视频采用中文 Windows SAPI 配音、同步字幕、程序化示意图和流畅转场合成，用于演示课程检索、详情、播放与学习进度闭环。

主题包括 Java 基础、并发、JVM、MySQL、Redis、RabbitMQ、Docker、Linux、Git、Vue 3、TypeScript、网络请求、Elasticsearch、Python、提示词、RAG、Agent、测试与系统设计。2026-10-08 本次新增21门、42章、42节视频，原有3门保留，课程列表共24门；视频总时长6868秒（约114分钟），MP4合计117227972字节（约112MiB）。数量是本次导入结果，后续用户新增课程不受限制。

这些内容是产品功能演示素材，不是完整的商业长课，也不应描述为真人、数字人或神经网络生成的视频。技术主题经过结构化编排，但仍应在正式发布前由对应领域人员审校。

## 本地媒体目录

生成后的媒体临时存放于：

```text
D:\Download\codex\learnhub-course-media
```

目录中的 `manifest.json` 记录每门课程的封面 URL、两个课时 URL 和播放器使用的真实视频时长。数据库 SQL 必须由该清单生成；生成工具还会逐一确认清单同目录下的 `courses/<slug>/cover.svg`、`lesson-01.mp4` 和 `lesson-02.mp4` 均存在且非空，并要求 URL 精确指向对应的 `localhost:8091` 路径。媒体缺失、课程数量不完整或时长不是正整数时会直接失败，不会写入占位地址或假时长。

本地 Docker 静态媒体服务通过 `http://localhost:8091` 提供文件，只面向当前开发机，不应直接暴露到公网。删除或移动上述临时目录后，本地视频会无法访问，需要重新执行媒体生成流程并重新生成课程 SQL。

## 课程入库

### 重新生成媒体

本机需要 Python 的 Pillow、imageio-ffmpeg 和 Windows 中文语音。生成工具不会调用收费视频服务，也不会上传素材：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/course-media/narrate.ps1 -OutputDir D:\Download\codex\learnhub-course-media
python scripts/course-media/render.py --output D:\Download\codex\learnhub-course-media --workers 3
python scripts/course-media/verify.py --output D:\Download\codex\learnhub-course-media
docker compose -f deploy/docker-compose.yml --profile demo up -d --no-deps course-media
```

视频为 960×540、24 FPS、H.264/AAC MP4，开启 faststart；Nginx 支持 HTTP Range，可拖动播放进度。中间配音文件放在 `_work`，语音按文案哈希缓存，修改讲解稿不会误复用旧配音。视频检查会完整解码每个文件、校验42个文件均不同，并比较不同时刻画面，避免把静态封面或同一段视频重复配给不同课程。字幕在场景内按文案比例分配，属于近似时间对齐，不是逐字强制对齐。

### 执行数据导入

媒体生成完成后，使用清单生成 SQL：

```powershell
node scripts/course-media/seed.mjs `
  --manifest D:\Download\codex\learnhub-course-media\manifest.json `
  --output sql\demo-courses.sql
```

生成的 SQL 具有以下边界：

- 只使用 `12001` 起的 21 个课程 ID，以及各自固定的章节、课时和标签 ID 范围；
- 仅执行幂等新增，不清理、不更新用户已有的课程或原始种子数据；
- 固定 ID 已被不匹配数据占用时，通过事务和 `SIGNAL` 中止，避免误关联；
- 每个课时都设置为免费预览，便于登录用户验收，不改变现有鉴权策略；
- 同一事务中写入搜索索引同步任务，后端同步任务随后将课程更新到 Elasticsearch。

执行 SQL 前应先确认本地媒体服务可访问。SQL 可以重复执行；已存在且标题、章节和媒体信息一致的数据会保留，不会覆盖用户编辑内容。

本次已在当前数据库导入 `sql/demo-courses.sql`，新课程21条搜索任务已由现有后端自动同步完成，ES 中可检索全部新课程。生成的SQL只导入本组短课，不能用 `sql/seed.sql` 中的问答初始化来代替，避免重置原有演示问答。复现导入时使用：

```powershell
docker cp sql/demo-courses.sql deploy-mysql-1:/tmp/learnhub-demo-courses-20261008.sql
docker exec deploy-mysql-1 mysql --default-character-set=utf8mb4 -ulearnhub -plearnhub -D learnhub -e "source /tmp/learnhub-demo-courses-20261008.sql"
node --test scripts/course-media/catalog.test.mjs
```

`learnhub_seed_demo_courses_20261008` 是此脚本专用的临时存储过程名称；首次导入前应确认没有用户自建的同名例程。脚本执行后删除该专用例程，保留课程、章节、课时和标签数据。

## 生产迁移建议

`localhost:8091` 只适合本机展示。部署到共享或生产环境时，应将封面和视频迁移到项目的媒体资源/OSS 流程，使用对象存储与 CDN 地址，并根据权限策略改为签名访问。迁移后需依据新的媒体清单重新生成受控迁移 SQL，不能继续依赖开发机的临时目录。
