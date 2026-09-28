SET NAMES utf8mb4;
USE learnhub;

INSERT IGNORE INTO course_category (id, name, sort_order, status) VALUES
    (1, '后端开发', 10, 'ENABLED'),
    (2, '人工智能', 20, 'ENABLED'),
    (3, '前端开发', 30, 'ENABLED');

INSERT IGNORE INTO course (id, category_id, title, subtitle, cover_url, description, instructor, price, status, published_at)
VALUES
    (1001, 1, 'Spring Boot 3 实战', '从 REST API 到高并发系统', '', '使用 Java 17 构建现代 Spring Boot 应用。', '林老师', 99.00, 'PUBLISHED', NOW(3)),
    (1002, 2, '大模型应用开发入门', 'RAG、Agent 与工程实践', '', '面向开发者的大模型应用课程。', '周老师', 129.00, 'PUBLISHED', NOW(3));

INSERT IGNORE INTO course_chapter (id, course_id, title, sort_order) VALUES
    (2001, 1001, '第一章：工程起步', 1),
    (2002, 1002, '第一章：大模型基础', 1);

INSERT IGNORE INTO course_lesson (id, chapter_id, title, media_url, duration_seconds, free_preview, sort_order) VALUES
    (3001, 2001, '创建第一个 Spring Boot 项目', 'https://example.com/video/spring-boot-intro.mp4', 900, 1, 1),
    (3002, 2002, '理解 Token 与上下文窗口', 'https://example.com/video/llm-token.mp4', 780, 1, 1);

INSERT IGNORE INTO tag (id, name) VALUES (1, 'Java'), (2, 'Spring Boot'), (3, 'AI');
INSERT IGNORE INTO course_tag (course_id, tag_id) VALUES (1001, 1), (1001, 2), (1002, 3);

INSERT IGNORE INTO coupon (
    id, name, type, threshold_amount, discount_amount, total_stock, available_stock,
    claim_start_at, claim_end_at, use_start_at, use_end_at, per_user_limit, status
) VALUES (
    4001, '新用户立减券', 'NORMAL', 50, 10, 1000, 1000,
    DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 30 DAY),
    NOW(3), DATE_ADD(NOW(3), INTERVAL 60 DAY), 1, 'ACTIVE'
), (
    4002, '限量秒杀券', 'SECKILL', 100, 30, 100, 100,
    DATE_SUB(NOW(3), INTERVAL 1 DAY), DATE_ADD(NOW(3), INTERVAL 7 DAY),
    NOW(3), DATE_ADD(NOW(3), INTERVAL 30 DAY), 1, 'ACTIVE'
);

-- 问答社区展示账号仅用于内容署名，禁止登录。
INSERT IGNORE INTO user (id, username, password_hash, nickname, status, token_version) VALUES
    (5001, 'demo_author_lin', 'DEMO_ACCOUNT_NO_LOGIN', '林序', 'DISABLED', 0),
    (5002, 'demo_author_zhou', 'DEMO_ACCOUNT_NO_LOGIN', '周栩', 'DISABLED', 0),
    (5003, 'demo_author_chen', 'DEMO_ACCOUNT_NO_LOGIN', '陈默', 'DISABLED', 0),
    (5004, 'demo_author_coder', 'DEMO_ACCOUNT_NO_LOGIN', '代码旅人', 'DISABLED', 0);

-- 固定 ID 范围仅供本地演示数据使用，重复执行时先重建该范围，避免计数漂移。
DELETE FROM answer WHERE id BETWEEN 7001 AND 7099;
DELETE FROM question WHERE id BETWEEN 6001 AND 6099;

INSERT INTO question (
    id, user_id, course_id, title, content, status, like_count, answer_count, created_at, updated_at, deleted
) VALUES
    (6001, 5001, 1001, 'Spring Boot 事务在异步方法中为什么会失效？',
     '在 Service 方法上添加了 @Transactional，内部调用带 @Async 的方法写入数据库。主方法回滚后，异步方法的数据仍然存在。这个场景应该怎样设计事务边界？',
     'OPEN', 42, 2, DATE_SUB(NOW(3), INTERVAL 3 DAY), DATE_SUB(NOW(3), INTERVAL 3 DAY), 0),
    (6002, 5002, 1002, 'RAG 分块大小应该怎样选择？',
     '正在为技术文档搭建 RAG。分块太小会丢失上下文，太大又影响召回精度。有哪些可以量化和验证的选择方法？',
     'OPEN', 35, 2, DATE_SUB(NOW(3), INTERVAL 2 DAY), DATE_SUB(NOW(3), INTERVAL 2 DAY), 0),
    (6003, 5003, NULL, 'Vue 3 中怎样避免接口 ID 精度丢失？',
     '后端返回的是 Java Long 类型 Snowflake ID，浏览器拿到后末尾数字发生变化。前后端应该如何统一 ID 类型？',
     'OPEN', 28, 2, DATE_SUB(NOW(3), INTERVAL 30 HOUR), DATE_SUB(NOW(3), INTERVAL 30 HOUR), 0),
    (6004, 5004, 1001, 'Redis Lua 预扣库存后，数据库失败该如何补偿？',
     '秒杀请求在 Redis 中预扣成功，但消息消费者写 MySQL 失败。怎样设计状态机、重试和对账，才能避免库存永久不一致？',
     'OPEN', 51, 2, DATE_SUB(NOW(3), INTERVAL 18 HOUR), DATE_SUB(NOW(3), INTERVAL 18 HOUR), 0),
    (6005, 5001, NULL, '从零学习后端，项目做到什么程度才适合写进简历？',
     '已经完成登录、课程列表和基础 CRUD。下一步应该优先补测试、部署，还是继续增加业务功能？希望得到一份可执行的检查清单。',
     'OPEN', 19, 1, DATE_SUB(NOW(3), INTERVAL 6 HOUR), DATE_SUB(NOW(3), INTERVAL 6 HOUR), 0);

INSERT INTO answer (
    id, question_id, user_id, content, status, like_count, accepted, created_at, updated_at, deleted
) VALUES
    (7001, 6001, 5003,
     '异步方法会在另一个线程中执行，无法继承调用线程绑定的数据库事务。应把异步任务视为独立事务：主事务先提交业务事实或 Outbox 事件，再由异步消费者开启自己的事务处理。不要依赖同类内部调用触发 @Async 或 @Transactional。',
     'VISIBLE', 31, 1, DATE_SUB(NOW(3), INTERVAL 68 HOUR), DATE_SUB(NOW(3), INTERVAL 68 HOUR), 0),
    (7002, 6001, 5004,
     '如果异步动作必须与主流程保持强一致，就不应异步化。若允许最终一致，应记录任务状态和幂等键，并提供失败重试与人工补偿入口。',
     'VISIBLE', 17, 0, DATE_SUB(NOW(3), INTERVAL 60 HOUR), DATE_SUB(NOW(3), INTERVAL 60 HOUR), 0),
    (7003, 6002, 5004,
     '先按文档结构切分，再用固定查询集做评估。可以从 300～500 tokens、10%～20% 重叠开始，记录 Recall@K、答案正确率和平均上下文长度，然后针对标题、表格和代码块单独制定切分规则。',
     'VISIBLE', 26, 1, DATE_SUB(NOW(3), INTERVAL 45 HOUR), DATE_SUB(NOW(3), INTERVAL 45 HOUR), 0),
    (7004, 6002, 5001,
     '不要只看分块长度。技术文档通常应保留章节标题作为元数据，并让代码块、步骤列表保持完整。最终选择要由真实问题集回归，而不是凭经验确定一个全局数字。',
     'VISIBLE', 14, 0, DATE_SUB(NOW(3), INTERVAL 39 HOUR), DATE_SUB(NOW(3), INTERVAL 39 HOUR), 0),
    (7005, 6003, 5001,
     'JavaScript 的安全整数上限是 2^53-1。后端应把 Long ID 序列化为字符串，TypeScript 也声明为 string，并在路由、缓存键和请求参数中始终保持字符串，不要在前端执行 Number(id)。',
     'VISIBLE', 34, 1, DATE_SUB(NOW(3), INTERVAL 26 HOUR), DATE_SUB(NOW(3), INTERVAL 26 HOUR), 0),
    (7006, 6003, 5002,
     '如果项目希望全局统一，可以为 Jackson 配置 Long 到 String 的序列化策略；如果只影响部分公开 DTO，也可以在对应字段上使用 ToStringSerializer，避免意外改变金额或统计字段。',
     'VISIBLE', 18, 0, DATE_SUB(NOW(3), INTERVAL 22 HOUR), DATE_SUB(NOW(3), INTERVAL 22 HOUR), 0),
    (7007, 6004, 5002,
     '为每次请求生成 requestId，并在数据库维护 PROCESSING、SUCCESS、FAILED 状态。消费者必须幂等；达到最大重试次数后进入死信队列。定时任务对比 Redis 预扣记录与数据库成功记录，确认失败后再执行库存回补。',
     'VISIBLE', 39, 1, DATE_SUB(NOW(3), INTERVAL 14 HOUR), DATE_SUB(NOW(3), INTERVAL 14 HOUR), 0),
    (7008, 6004, 5003,
     '补偿脚本同样要幂等，建议使用状态条件更新或补偿日志唯一键，避免消息重投导致重复加库存。关键指标至少包括消息积压、失败数、账实差异和补偿耗时。',
     'VISIBLE', 21, 0, DATE_SUB(NOW(3), INTERVAL 10 HOUR), DATE_SUB(NOW(3), INTERVAL 10 HOUR), 0),
    (7009, 6005, 5004,
     '优先把已有功能做成可验证、可部署的闭环：补关键业务测试、统一错误处理、数据库迁移、容器化部署和一份清晰的架构说明。相比继续堆 CRUD，这些内容更能证明你理解工程质量和线上运行。',
     'VISIBLE', 23, 1, DATE_SUB(NOW(3), INTERVAL 3 HOUR), DATE_SUB(NOW(3), INTERVAL 3 HOUR), 0);
