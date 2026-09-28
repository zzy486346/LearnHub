SET NAMES utf8mb4;
USE learnhub;

UPDATE course_category SET name = '后端开发' WHERE id = 1;
UPDATE course_category SET name = '人工智能' WHERE id = 2;
UPDATE course_category SET name = '前端开发' WHERE id = 3;

UPDATE course
SET title = 'Spring Boot 3 实战',
    subtitle = '从 REST API 到高并发系统',
    description = '使用 Java 17 构建现代 Spring Boot 应用。',
    instructor = '林老师'
WHERE id = 1001;

UPDATE course
SET title = '大模型应用开发入门',
    subtitle = 'RAG、Agent 与工程实践',
    description = '面向开发者的大模型应用课程。',
    instructor = '周老师'
WHERE id = 1002;

UPDATE course_chapter SET title = '第一章：工程起步' WHERE id = 2001;
UPDATE course_chapter SET title = '第一章：大模型基础' WHERE id = 2002;

UPDATE course_lesson SET title = '创建第一个 Spring Boot 项目' WHERE id = 3001;
UPDATE course_lesson SET title = '理解 Token 与上下文窗口' WHERE id = 3002;

UPDATE coupon SET name = '新用户立减券' WHERE id = 4001;
UPDATE coupon SET name = '限量秒杀券' WHERE id = 4002;
