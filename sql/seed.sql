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
