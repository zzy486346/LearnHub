-- 此文件由 scripts/course-media/seed.mjs 生成，请勿手工修改。
-- 内容为本地演示短课；MySQL 是事实源，媒体由 localhost:8091 提供。
SET NAMES utf8mb4;
USE learnhub;

DELIMITER $$
DROP PROCEDURE IF EXISTS learnhub_seed_demo_courses_20261008$$
CREATE PROCEDURE learnhub_seed_demo_courses_20261008()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        ROLLBACK;
        RESIGNAL;
    END;

    START TRANSACTION;

    IF EXISTS (SELECT 1 FROM course WHERE id = 12001 AND title <> 'Java 编程基础入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12001 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22011 AND (course_id <> 12001 OR title <> '从输入到计算结果')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22011 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32011 AND (chapter_id <> 22011 OR title <> '变量和方法怎样配合' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/java-basics/lesson-01.mp4' OR duration_seconds <> 161 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32011 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22012 AND (course_id <> 12001 OR title <> '让代码接近业务语言')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22012 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32012 AND (chapter_id <> 22012 OR title <> '对象如何表达业务职责' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/java-basics/lesson-02.mp4' OR duration_seconds <> 169 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32012 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12002 AND title <> 'Java 并发编程入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12002 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22021 AND (course_id <> 12002 OR title <> '识别共享状态竞争')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22021 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32021 AND (chapter_id <> 22021 OR title <> '库存为何会被重复扣减' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/java-concurrency/lesson-01.mp4' OR duration_seconds <> 161 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32021 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22022 AND (course_id <> 12002 OR title <> '控制并发而非堆线程')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22022 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32022 AND (chapter_id <> 22022 OR title <> '线程池如何承接异步任务' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/java-concurrency/lesson-02.mp4' OR duration_seconds <> 155 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32022 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12003 AND title <> 'JVM 运行原理入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12003 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22031 AND (course_id <> 12003 OR title <> '从创建到被回收')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22031 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32031 AND (chapter_id <> 22031 OR title <> '对象在内存里经历什么' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/jvm/lesson-01.mp4' OR duration_seconds <> 162 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32031 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22032 AND (course_id <> 12003 OR title <> '用证据定位保留链')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22032 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32032 AND (chapter_id <> 22032 OR title <> '内存持续上涨怎么查' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/jvm/lesson-02.mp4' OR duration_seconds <> 173 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32032 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12004 AND title <> 'MySQL 数据库基础入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12004 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22041 AND (course_id <> 12004 OR title <> '字段、约束与关系')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22041 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32041 AND (chapter_id <> 22041 OR title <> '订单表如何表达事实' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/mysql-basics/lesson-01.mp4' OR duration_seconds <> 160 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32041 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22042 AND (course_id <> 12004 OR title <> '让多步操作共同成败')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22042 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32042 AND (chapter_id <> 22042 OR title <> '转账为什么需要事务' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/mysql-basics/lesson-02.mp4' OR duration_seconds <> 169 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32042 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12005 AND title <> 'MySQL 索引优化入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12005 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22051 AND (course_id <> 12005 OR title <> '围绕访问路径设计')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22051 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32051 AND (chapter_id <> 22051 OR title <> '联合索引怎样匹配查询' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/mysql-index/lesson-01.mp4' OR duration_seconds <> 161 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32051 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22052 AND (course_id <> 12005 OR title <> '验证而不是猜优化')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22052 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32052 AND (chapter_id <> 22052 OR title <> '执行计划告诉了什么' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/mysql-index/lesson-02.mp4' OR duration_seconds <> 167 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32052 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12006 AND title <> 'Redis 数据结构入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12006 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22061 AND (course_id <> 12006 OR title <> '位图表达真假状态')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22061 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32061 AND (chapter_id <> 22061 OR title <> '签到记录怎么存更紧凑' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/redis-basics/lesson-01.mp4' OR duration_seconds <> 167 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32061 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22062 AND (course_id <> 12006 OR title <> '有序集合维护名次')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22062 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32062 AND (chapter_id <> 22062 OR title <> '积分排行榜如何更新' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/redis-basics/lesson-02.mp4' OR duration_seconds <> 163 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32062 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12007 AND title <> 'Redis 缓存治理入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12007 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22071 AND (course_id <> 12007 OR title <> '避免请求同时回源')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22071 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32071 AND (chapter_id <> 22071 OR title <> '热点键过期时发生什么' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/redis-cache/lesson-01.mp4' OR duration_seconds <> 164 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32071 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22072 AND (course_id <> 12007 OR title <> '让缓存接受短暂延迟')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22072 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32072 AND (chapter_id <> 22072 OR title <> '更新商品如何保持一致' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/redis-cache/lesson-02.mp4' OR duration_seconds <> 161 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32072 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12008 AND title <> 'RabbitMQ 消息队列入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12008 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22081 AND (course_id <> 12008 OR title <> '交换机与队列协作')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22081 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32081 AND (chapter_id <> 22081 OR title <> '订单消息如何到达消费者' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/rabbitmq/lesson-01.mp4' OR duration_seconds <> 164 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32081 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22082 AND (course_id <> 12008 OR title <> '确认与幂等共同兜底')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22082 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32082 AND (chapter_id <> 22082 OR title <> '重复消息如何安全处理' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/rabbitmq/lesson-02.mp4' OR duration_seconds <> 159 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32082 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12009 AND title <> 'Docker 容器化入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12009 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22091 AND (course_id <> 12009 OR title <> '构建可重复交付物')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22091 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32091 AND (chapter_id <> 22091 OR title <> '镜像如何封装应用' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/docker/lesson-01.mp4' OR duration_seconds <> 164 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32091 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22092 AND (course_id <> 12009 OR title <> 'Compose 描述本地拓扑')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22092 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32092 AND (chapter_id <> 22092 OR title <> '多个服务怎样一起启动' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/docker/lesson-02.mp4' OR duration_seconds <> 160 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32092 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12010 AND title <> 'Linux 服务排查入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12010 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22101 AND (course_id <> 12010 OR title <> '从网络入口逐层确认')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22101 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32101 AND (chapter_id <> 22101 OR title <> '接口连不上先查哪里' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/linux/lesson-01.mp4' OR duration_seconds <> 165 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32101 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22102 AND (course_id <> 12010 OR title <> '把现象关联到进程')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22102 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32102 AND (chapter_id <> 22102 OR title <> '资源升高怎样缩小范围' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/linux/lesson-02.mp4' OR duration_seconds <> 156 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32102 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12011 AND title <> 'Git 协作基础入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12011 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22111 AND (course_id <> 12011 OR title <> '构造清晰可回退历史')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22111 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32111 AND (chapter_id <> 22111 OR title <> '一次提交应该装什么' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/git/lesson-01.mp4' OR duration_seconds <> 155 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32111 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22112 AND (course_id <> 12011 OR title <> '理解双方意图再合并')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22112 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32112 AND (chapter_id <> 22112 OR title <> '冲突怎样安全解决' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/git/lesson-02.mp4' OR duration_seconds <> 167 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32112 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12012 AND title <> 'Vue 3 组件开发入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12012 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22121 AND (course_id <> 12012 OR title <> '建立响应式数据流')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22121 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32121 AND (chapter_id <> 22121 OR title <> '筛选条件如何驱动列表' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/vue3/lesson-01.mp4' OR duration_seconds <> 161 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32121 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22122 AND (course_id <> 12012 OR title <> '单向数据与事件上报')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22122 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32122 AND (chapter_id <> 22122 OR title <> '卡片组件如何与页面通信' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/vue3/lesson-02.mp4' OR duration_seconds <> 159 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32122 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12013 AND title <> 'TypeScript 类型建模入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12013 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22131 AND (course_id <> 12013 OR title <> '用联合类型收紧取值')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22131 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32131 AND (chapter_id <> 22131 OR title <> '课程状态如何避免魔法字符串' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/typescript/lesson-01.mp4' OR duration_seconds <> 155 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32131 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22132 AND (course_id <> 12013 OR title <> '区分外部数据与内部类型')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22132 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32132 AND (chapter_id <> 22132 OR title <> '接口响应怎样安全进入页面' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/typescript/lesson-02.mp4' OR duration_seconds <> 167 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32132 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12014 AND title <> '前端网络请求入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12014 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22141 AND (course_id <> 12014 OR title <> '控制并发请求竞态')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22141 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32141 AND (chapter_id <> 22141 OR title <> '实时搜索为何会结果倒退' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/frontend-network/lesson-01.mp4' OR duration_seconds <> 162 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32141 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22142 AND (course_id <> 12014 OR title <> '在请求边界恢复会话')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22142 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32142 AND (chapter_id <> 22142 OR title <> '登录失效怎样统一处理' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/frontend-network/lesson-02.mp4' OR duration_seconds <> 170 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32142 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12015 AND title <> 'Elasticsearch 搜索入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12015 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22151 AND (course_id <> 12015 OR title <> '从文档转为词项清单')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22151 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32151 AND (chapter_id <> 22151 OR title <> '倒排索引怎样找到课程' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/elasticsearch/lesson-01.mp4' OR duration_seconds <> 164 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32151 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22152 AND (course_id <> 12015 OR title <> '让文本匹配主导排序')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22152 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32152 AND (chapter_id <> 22152 OR title <> '相关性为何不只看热度' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/elasticsearch/lesson-02.mp4' OR duration_seconds <> 156 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32152 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12016 AND title <> 'Python 自动化入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12016 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22161 AND (course_id <> 12016 OR title <> '小内存完成大文件解析')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22161 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32161 AND (chapter_id <> 22161 OR title <> '日志文件怎样逐行处理' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/python/lesson-01.mp4' OR duration_seconds <> 167 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32161 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22162 AND (course_id <> 12016 OR title <> '聚合、排序与原子写入')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22162 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32162 AND (chapter_id <> 22162 OR title <> '统计报告如何稳定生成' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/python/lesson-02.mp4' OR duration_seconds <> 161 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32162 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12017 AND title <> '提示词工程入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12017 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22171 AND (course_id <> 12017 OR title <> '补齐目标、输入与约束')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22171 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32171 AND (chapter_id <> 22171 OR title <> '怎样写清课程摘要任务' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/prompt-engineering/lesson-01.mp4' OR duration_seconds <> 162 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32171 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22172 AND (course_id <> 12017 OR title <> '用样本而非感觉迭代')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22172 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32172 AND (chapter_id <> 22172 OR title <> '工单分类提示怎样评测' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/prompt-engineering/lesson-02.mp4' OR duration_seconds <> 169 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32172 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12018 AND title <> 'RAG 知识问答入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12018 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22181 AND (course_id <> 12018 OR title <> '切分时保留语义上下文')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22181 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32181 AND (chapter_id <> 22181 OR title <> '长文档怎样变成可检索片段' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/rag/lesson-01.mp4' OR duration_seconds <> 167 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32181 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22182 AND (course_id <> 12018 OR title <> '检索、筛选与引用')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22182 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32182 AND (chapter_id <> 22182 OR title <> '回答如何做到有依据' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/rag/lesson-02.mp4' OR duration_seconds <> 167 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32182 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12019 AND title <> 'AI Agent 工作流入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12019 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22191 AND (course_id <> 12019 OR title <> '从意图到结构化动作')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22191 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32191 AND (chapter_id <> 22191 OR title <> '选课助理怎样调用工具' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/agent/lesson-01.mp4' OR duration_seconds <> 163 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32191 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22192 AND (course_id <> 12019 OR title <> '给 Agent 设置权限边界')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22192 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32192 AND (chapter_id <> 22192 OR title <> '高风险动作为何要确认' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/agent/lesson-02.mp4' OR duration_seconds <> 164 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32192 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12020 AND title <> '软件测试实践入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12020 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22201 AND (course_id <> 12020 OR title <> '围绕行为覆盖边界')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22201 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32201 AND (chapter_id <> 22201 OR title <> '优惠券规则如何写单元测试' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/testing/lesson-01.mp4' OR duration_seconds <> 172 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32201 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22202 AND (course_id <> 12020 OR title <> '检查跨层真实协作')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22202 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32202 AND (chapter_id <> 22202 OR title <> '领取接口怎样做集成验证' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/testing/lesson-02.mp4' OR duration_seconds <> 160 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32202 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course WHERE id = 12021 AND title <> '系统设计基础入门短课') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课程 ID 12021 已被其他课程占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22211 AND (course_id <> 12021 OR title <> '先画清核心读链路')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22211 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32211 AND (chapter_id <> 22211 OR title <> '课程详情请求经过哪些层' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/system-design/lesson-01.mp4' OR duration_seconds <> 173 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32211 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM course_chapter WHERE id = 22212 AND (course_id <> 12021 OR title <> '用事件隔离附加功能')) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示章节 ID 22212 已被其他数据占用';
    END IF;
    IF EXISTS (SELECT 1 FROM course_lesson WHERE id = 32212 AND (chapter_id <> 22212 OR title <> '学习进度如何异步扩展' OR COALESCE(media_url, '') <> 'http://localhost:8091/courses/system-design/lesson-02.mp4' OR duration_seconds <> 166 OR free_preview <> 1 OR sort_order <> 1)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示课时 ID 32212 已存在不匹配数据';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51000 AND name <> '版本控制') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '版本控制') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51000 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51001 AND name <> '编程基础') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '编程基础') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51001 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51002 AND name <> '并发') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '并发') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51002 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51003 AND name <> '部署') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '部署') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51003 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51004 AND name <> '大模型') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '大模型') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51004 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51005 AND name <> '单元测试') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '单元测试') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51005 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51006 AND name <> '高并发') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '高并发') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51006 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51007 AND name <> '高可用') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '高可用') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51007 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51008 AND name <> '工具调用') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '工具调用') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51008 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51009 AND name <> '工作流') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '工作流') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51009 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51010 AND name <> '故障排查') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '故障排查') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51010 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51011 AND name <> '缓存') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '缓存') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51011 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51012 AND name <> '架构') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '架构') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51012 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51013 AND name <> '接口测试') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '接口测试') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51013 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51014 AND name <> '可靠性') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '可靠性') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51014 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51015 AND name <> '类型系统') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '类型系统') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51015 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51016 AND name <> '面向对象') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '面向对象') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51016 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51017 AND name <> '评测') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '评测') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51017 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51018 AND name <> '前端') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '前端') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51018 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51019 AND name <> '前端工程') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '前端工程') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51019 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51020 AND name <> '全文检索') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '全文检索') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51020 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51021 AND name <> '容器') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '容器') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51021 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51022 AND name <> '软件测试') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '软件测试') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51022 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51023 AND name <> '事务') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '事务') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51023 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51024 AND name <> '数据处理') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '数据处理') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51024 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51025 AND name <> '数据结构') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '数据结构') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51025 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51026 AND name <> '索引') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '索引') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51026 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51027 AND name <> '提示词') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '提示词') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51027 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51028 AND name <> '团队协作') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '团队协作') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51028 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51029 AND name <> '系统设计') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '系统设计') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51029 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51030 AND name <> '线程安全') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '线程安全') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51030 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51031 AND name <> '响应式') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '响应式') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51031 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51032 AND name <> '向量检索') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '向量检索') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51032 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51033 AND name <> '消息队列') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '消息队列') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51033 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51034 AND name <> '性能排查') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '性能排查') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51034 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51035 AND name <> '用户体验') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '用户体验') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51035 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51036 AND name <> '运维') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '运维') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51036 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51037 AND name <> '中文分词') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '中文分词') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51037 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51038 AND name <> '自动化') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '自动化') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51038 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51039 AND name <> '组件') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = '组件') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51039 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51040 AND name <> 'AI Agent') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'AI Agent') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51040 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51041 AND name <> 'Docker') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'Docker') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51041 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51042 AND name <> 'Elasticsearch') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'Elasticsearch') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51042 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51043 AND name <> 'Git') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'Git') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51043 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51044 AND name <> 'HTTP') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'HTTP') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51044 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51045 AND name <> 'Java') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'Java') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51045 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51046 AND name <> 'JVM') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'JVM') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51046 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51047 AND name <> 'Linux') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'Linux') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51047 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51048 AND name <> 'MySQL') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'MySQL') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51048 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51049 AND name <> 'Python') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'Python') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51049 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51050 AND name <> 'RabbitMQ') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'RabbitMQ') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51050 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51051 AND name <> 'RAG') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'RAG') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51051 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51052 AND name <> 'Redis') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'Redis') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51052 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51053 AND name <> 'SQL') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'SQL') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51053 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51054 AND name <> 'SQL优化') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'SQL优化') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51054 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51055 AND name <> 'TypeScript') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'TypeScript') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51055 已被其他标签占用';
    END IF;
    IF EXISTS (SELECT 1 FROM tag WHERE id = 51056 AND name <> 'Vue3') AND NOT EXISTS (SELECT 1 FROM tag WHERE name = 'Vue3') THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '演示标签 ID 51056 已被其他标签占用';
    END IF;

    INSERT IGNORE INTO course (
        id, category_id, title, subtitle, cover_url, description, instructor,
        price, status, published_at
    ) VALUES
        (12001, 1, 'Java 编程基础入门短课', '从类型、方法到一个可运行的订单价格计算器', 'http://localhost:8091/courses/java-basics/cover.svg', '两节动画讲解短片，用订单计价小例子理解 Java 基础语法、对象职责与异常边界。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12002, 1, 'Java 并发编程入门短课', '用库存扣减理解线程安全与任务编排', 'http://localhost:8091/courses/java-concurrency/cover.svg', '两节动画讲解短片，围绕抢购库存和异步报表，建立 Java 并发的工程直觉。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12003, 1, 'JVM 运行原理入门短课', '看懂对象内存、垃圾回收与一次故障排查', 'http://localhost:8091/courses/jvm/cover.svg', '两节动画讲解短片，从对象生命周期和内存上涨案例理解 JVM 的稳定运行机制。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12004, 1, 'MySQL 数据库基础入门短课', '设计订单表并写出可靠的事务操作', 'http://localhost:8091/courses/mysql-basics/cover.svg', '两节动画讲解短片，通过订单表建模与转账事务掌握 MySQL 基础能力。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12005, 1, 'MySQL 索引优化入门短课', '从慢订单查询到可解释的索引设计', 'http://localhost:8091/courses/mysql-index/cover.svg', '两节动画讲解短片，用订单列表与分页案例理解联合索引和执行计划。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12006, 1, 'Redis 数据结构入门短课', '用签到和排行榜理解内存数据结构', 'http://localhost:8091/courses/redis-basics/cover.svg', '两节动画讲解短片，以每日签到和积分排行介绍 Redis 的选型思路。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12007, 1, 'Redis 缓存治理入门短课', '处理热点商品的穿透、击穿与一致性', 'http://localhost:8091/courses/redis-cache/cover.svg', '两节动画讲解短片，从商品详情缓存案例认识失效风险和更新策略。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12008, 1, 'RabbitMQ 消息队列入门短课', '拆解下单通知与可靠消费流程', 'http://localhost:8091/courses/rabbitmq/cover.svg', '两节动画讲解短片，用订单事件说明消息路由、确认、幂等与重试。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12009, 1, 'Docker 容器化入门短课', '把一个 Web 服务做成可重复运行的镜像', 'http://localhost:8091/courses/docker/cover.svg', '两节动画讲解短片，介绍镜像分层、容器运行与 Compose 服务编排。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12010, 1, 'Linux 服务排查入门短课', '沿进程、端口和日志定位接口故障', 'http://localhost:8091/courses/linux/cover.svg', '两节动画讲解短片，用接口不可用与资源升高案例练习 Linux 排查路径。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12011, 1, 'Git 协作基础入门短课', '理解提交历史并安全解决一次冲突', 'http://localhost:8091/courses/git/cover.svg', '两节动画讲解短片，通过功能分支与冲突修复建立 Git 协作习惯。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12012, 3, 'Vue 3 组件开发入门短课', '用课程卡片理解响应式状态与组件通信', 'http://localhost:8091/courses/vue3/cover.svg', '两节动画讲解短片，通过课程列表构建 Vue 3 的响应式与组件边界。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12013, 3, 'TypeScript 类型建模入门短课', '让课程接口数据在编译期暴露问题', 'http://localhost:8091/courses/typescript/cover.svg', '两节动画讲解短片，使用课程状态和接口响应演示实用类型建模。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12014, 3, '前端网络请求入门短课', '处理课程搜索的竞态、超时与登录失效', 'http://localhost:8091/courses/frontend-network/cover.svg', '两节动画讲解短片，通过实时搜索和认证过期案例改善请求体验。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12015, 1, 'Elasticsearch 搜索入门短课', '为中文课程库构建可解释的全文检索', 'http://localhost:8091/courses/elasticsearch/cover.svg', '两节动画讲解短片，从倒排索引、中文分词到相关性排序理解课程搜索。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12016, 2, 'Python 自动化入门短课', '用日志整理脚本掌握数据处理和错误边界', 'http://localhost:8091/courses/python/cover.svg', '两节动画讲解短片，从读取日志到生成统计报告练习 Python 自动化。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12017, 2, '提示词工程入门短课', '把模糊需求变成可验证的模型任务', 'http://localhost:8091/courses/prompt-engineering/cover.svg', '两节动画讲解短片，以课程摘要和工单分类讲解提示词结构与评测。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12018, 2, 'RAG 知识问答入门短课', '让模型基于课程文档回答并给出依据', 'http://localhost:8091/courses/rag/cover.svg', '两节动画讲解短片，拆解文档切分、检索召回和有依据回答的完整链路。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12019, 2, 'AI Agent 工作流入门短课', '用课程助理理解工具调用、状态与安全边界', 'http://localhost:8091/courses/agent/cover.svg', '两节动画讲解短片，通过选课助理案例认识 Agent 的决策循环和工具治理。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12020, 1, '软件测试实践入门短课', '从优惠券规则到可靠的接口回归测试', 'http://localhost:8091/courses/testing/cover.svg', '两节动画讲解短片，用优惠券计算和领取接口说明测试分层与边界覆盖。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3)),
        (12021, 1, '系统设计基础入门短课', '设计一个可扩展的在线学习平台', 'http://localhost:8091/courses/system-design/cover.svg', '两节动画讲解短片，从请求链路和异步事件出发理解系统边界与演进。', 'LearnHub 讲解组', 0.00, 'PUBLISHED', NOW(3));

    INSERT IGNORE INTO course_chapter (id, course_id, title, sort_order) VALUES
        (22011, 12001, '从输入到计算结果', 1),
        (22012, 12001, '让代码接近业务语言', 2),
        (22021, 12002, '识别共享状态竞争', 1),
        (22022, 12002, '控制并发而非堆线程', 2),
        (22031, 12003, '从创建到被回收', 1),
        (22032, 12003, '用证据定位保留链', 2),
        (22041, 12004, '字段、约束与关系', 1),
        (22042, 12004, '让多步操作共同成败', 2),
        (22051, 12005, '围绕访问路径设计', 1),
        (22052, 12005, '验证而不是猜优化', 2),
        (22061, 12006, '位图表达真假状态', 1),
        (22062, 12006, '有序集合维护名次', 2),
        (22071, 12007, '避免请求同时回源', 1),
        (22072, 12007, '让缓存接受短暂延迟', 2),
        (22081, 12008, '交换机与队列协作', 1),
        (22082, 12008, '确认与幂等共同兜底', 2),
        (22091, 12009, '构建可重复交付物', 1),
        (22092, 12009, 'Compose 描述本地拓扑', 2),
        (22101, 12010, '从网络入口逐层确认', 1),
        (22102, 12010, '把现象关联到进程', 2),
        (22111, 12011, '构造清晰可回退历史', 1),
        (22112, 12011, '理解双方意图再合并', 2),
        (22121, 12012, '建立响应式数据流', 1),
        (22122, 12012, '单向数据与事件上报', 2),
        (22131, 12013, '用联合类型收紧取值', 1),
        (22132, 12013, '区分外部数据与内部类型', 2),
        (22141, 12014, '控制并发请求竞态', 1),
        (22142, 12014, '在请求边界恢复会话', 2),
        (22151, 12015, '从文档转为词项清单', 1),
        (22152, 12015, '让文本匹配主导排序', 2),
        (22161, 12016, '小内存完成大文件解析', 1),
        (22162, 12016, '聚合、排序与原子写入', 2),
        (22171, 12017, '补齐目标、输入与约束', 1),
        (22172, 12017, '用样本而非感觉迭代', 2),
        (22181, 12018, '切分时保留语义上下文', 1),
        (22182, 12018, '检索、筛选与引用', 2),
        (22191, 12019, '从意图到结构化动作', 1),
        (22192, 12019, '给 Agent 设置权限边界', 2),
        (22201, 12020, '围绕行为覆盖边界', 1),
        (22202, 12020, '检查跨层真实协作', 2),
        (22211, 12021, '先画清核心读链路', 1),
        (22212, 12021, '用事件隔离附加功能', 2);

    INSERT IGNORE INTO course_lesson (
        id, chapter_id, title, media_url, duration_seconds, free_preview, sort_order
    ) VALUES
        (32011, 22011, '变量和方法怎样配合', 'http://localhost:8091/courses/java-basics/lesson-01.mp4', 161, 1, 1),
        (32012, 22012, '对象如何表达业务职责', 'http://localhost:8091/courses/java-basics/lesson-02.mp4', 169, 1, 1),
        (32021, 22021, '库存为何会被重复扣减', 'http://localhost:8091/courses/java-concurrency/lesson-01.mp4', 161, 1, 1),
        (32022, 22022, '线程池如何承接异步任务', 'http://localhost:8091/courses/java-concurrency/lesson-02.mp4', 155, 1, 1),
        (32031, 22031, '对象在内存里经历什么', 'http://localhost:8091/courses/jvm/lesson-01.mp4', 162, 1, 1),
        (32032, 22032, '内存持续上涨怎么查', 'http://localhost:8091/courses/jvm/lesson-02.mp4', 173, 1, 1),
        (32041, 22041, '订单表如何表达事实', 'http://localhost:8091/courses/mysql-basics/lesson-01.mp4', 160, 1, 1),
        (32042, 22042, '转账为什么需要事务', 'http://localhost:8091/courses/mysql-basics/lesson-02.mp4', 169, 1, 1),
        (32051, 22051, '联合索引怎样匹配查询', 'http://localhost:8091/courses/mysql-index/lesson-01.mp4', 161, 1, 1),
        (32052, 22052, '执行计划告诉了什么', 'http://localhost:8091/courses/mysql-index/lesson-02.mp4', 167, 1, 1),
        (32061, 22061, '签到记录怎么存更紧凑', 'http://localhost:8091/courses/redis-basics/lesson-01.mp4', 167, 1, 1),
        (32062, 22062, '积分排行榜如何更新', 'http://localhost:8091/courses/redis-basics/lesson-02.mp4', 163, 1, 1),
        (32071, 22071, '热点键过期时发生什么', 'http://localhost:8091/courses/redis-cache/lesson-01.mp4', 164, 1, 1),
        (32072, 22072, '更新商品如何保持一致', 'http://localhost:8091/courses/redis-cache/lesson-02.mp4', 161, 1, 1),
        (32081, 22081, '订单消息如何到达消费者', 'http://localhost:8091/courses/rabbitmq/lesson-01.mp4', 164, 1, 1),
        (32082, 22082, '重复消息如何安全处理', 'http://localhost:8091/courses/rabbitmq/lesson-02.mp4', 159, 1, 1),
        (32091, 22091, '镜像如何封装应用', 'http://localhost:8091/courses/docker/lesson-01.mp4', 164, 1, 1),
        (32092, 22092, '多个服务怎样一起启动', 'http://localhost:8091/courses/docker/lesson-02.mp4', 160, 1, 1),
        (32101, 22101, '接口连不上先查哪里', 'http://localhost:8091/courses/linux/lesson-01.mp4', 165, 1, 1),
        (32102, 22102, '资源升高怎样缩小范围', 'http://localhost:8091/courses/linux/lesson-02.mp4', 156, 1, 1),
        (32111, 22111, '一次提交应该装什么', 'http://localhost:8091/courses/git/lesson-01.mp4', 155, 1, 1),
        (32112, 22112, '冲突怎样安全解决', 'http://localhost:8091/courses/git/lesson-02.mp4', 167, 1, 1),
        (32121, 22121, '筛选条件如何驱动列表', 'http://localhost:8091/courses/vue3/lesson-01.mp4', 161, 1, 1),
        (32122, 22122, '卡片组件如何与页面通信', 'http://localhost:8091/courses/vue3/lesson-02.mp4', 159, 1, 1),
        (32131, 22131, '课程状态如何避免魔法字符串', 'http://localhost:8091/courses/typescript/lesson-01.mp4', 155, 1, 1),
        (32132, 22132, '接口响应怎样安全进入页面', 'http://localhost:8091/courses/typescript/lesson-02.mp4', 167, 1, 1),
        (32141, 22141, '实时搜索为何会结果倒退', 'http://localhost:8091/courses/frontend-network/lesson-01.mp4', 162, 1, 1),
        (32142, 22142, '登录失效怎样统一处理', 'http://localhost:8091/courses/frontend-network/lesson-02.mp4', 170, 1, 1),
        (32151, 22151, '倒排索引怎样找到课程', 'http://localhost:8091/courses/elasticsearch/lesson-01.mp4', 164, 1, 1),
        (32152, 22152, '相关性为何不只看热度', 'http://localhost:8091/courses/elasticsearch/lesson-02.mp4', 156, 1, 1),
        (32161, 22161, '日志文件怎样逐行处理', 'http://localhost:8091/courses/python/lesson-01.mp4', 167, 1, 1),
        (32162, 22162, '统计报告如何稳定生成', 'http://localhost:8091/courses/python/lesson-02.mp4', 161, 1, 1),
        (32171, 22171, '怎样写清课程摘要任务', 'http://localhost:8091/courses/prompt-engineering/lesson-01.mp4', 162, 1, 1),
        (32172, 22172, '工单分类提示怎样评测', 'http://localhost:8091/courses/prompt-engineering/lesson-02.mp4', 169, 1, 1),
        (32181, 22181, '长文档怎样变成可检索片段', 'http://localhost:8091/courses/rag/lesson-01.mp4', 167, 1, 1),
        (32182, 22182, '回答如何做到有依据', 'http://localhost:8091/courses/rag/lesson-02.mp4', 167, 1, 1),
        (32191, 22191, '选课助理怎样调用工具', 'http://localhost:8091/courses/agent/lesson-01.mp4', 163, 1, 1),
        (32192, 22192, '高风险动作为何要确认', 'http://localhost:8091/courses/agent/lesson-02.mp4', 164, 1, 1),
        (32201, 22201, '优惠券规则如何写单元测试', 'http://localhost:8091/courses/testing/lesson-01.mp4', 172, 1, 1),
        (32202, 22202, '领取接口怎样做集成验证', 'http://localhost:8091/courses/testing/lesson-02.mp4', 160, 1, 1),
        (32211, 22211, '课程详情请求经过哪些层', 'http://localhost:8091/courses/system-design/lesson-01.mp4', 173, 1, 1),
        (32212, 22212, '学习进度如何异步扩展', 'http://localhost:8091/courses/system-design/lesson-02.mp4', 166, 1, 1);

    INSERT IGNORE INTO tag (id, name) VALUES
        (51000, '版本控制'),
        (51001, '编程基础'),
        (51002, '并发'),
        (51003, '部署'),
        (51004, '大模型'),
        (51005, '单元测试'),
        (51006, '高并发'),
        (51007, '高可用'),
        (51008, '工具调用'),
        (51009, '工作流'),
        (51010, '故障排查'),
        (51011, '缓存'),
        (51012, '架构'),
        (51013, '接口测试'),
        (51014, '可靠性'),
        (51015, '类型系统'),
        (51016, '面向对象'),
        (51017, '评测'),
        (51018, '前端'),
        (51019, '前端工程'),
        (51020, '全文检索'),
        (51021, '容器'),
        (51022, '软件测试'),
        (51023, '事务'),
        (51024, '数据处理'),
        (51025, '数据结构'),
        (51026, '索引'),
        (51027, '提示词'),
        (51028, '团队协作'),
        (51029, '系统设计'),
        (51030, '线程安全'),
        (51031, '响应式'),
        (51032, '向量检索'),
        (51033, '消息队列'),
        (51034, '性能排查'),
        (51035, '用户体验'),
        (51036, '运维'),
        (51037, '中文分词'),
        (51038, '自动化'),
        (51039, '组件'),
        (51040, 'AI Agent'),
        (51041, 'Docker'),
        (51042, 'Elasticsearch'),
        (51043, 'Git'),
        (51044, 'HTTP'),
        (51045, 'Java'),
        (51046, 'JVM'),
        (51047, 'Linux'),
        (51048, 'MySQL'),
        (51049, 'Python'),
        (51050, 'RabbitMQ'),
        (51051, 'RAG'),
        (51052, 'Redis'),
        (51053, 'SQL'),
        (51054, 'SQL优化'),
        (51055, 'TypeScript'),
        (51056, 'Vue3');

    INSERT IGNORE INTO course_tag (course_id, tag_id)
    SELECT 12001, id FROM tag WHERE name = 'Java'
    UNION ALL SELECT 12001, id FROM tag WHERE name = '编程基础'
    UNION ALL SELECT 12001, id FROM tag WHERE name = '面向对象'
    UNION ALL SELECT 12002, id FROM tag WHERE name = 'Java'
    UNION ALL SELECT 12002, id FROM tag WHERE name = '并发'
    UNION ALL SELECT 12002, id FROM tag WHERE name = '线程安全'
    UNION ALL SELECT 12003, id FROM tag WHERE name = 'JVM'
    UNION ALL SELECT 12003, id FROM tag WHERE name = 'Java'
    UNION ALL SELECT 12003, id FROM tag WHERE name = '性能排查'
    UNION ALL SELECT 12004, id FROM tag WHERE name = 'MySQL'
    UNION ALL SELECT 12004, id FROM tag WHERE name = 'SQL'
    UNION ALL SELECT 12004, id FROM tag WHERE name = '事务'
    UNION ALL SELECT 12005, id FROM tag WHERE name = 'MySQL'
    UNION ALL SELECT 12005, id FROM tag WHERE name = '索引'
    UNION ALL SELECT 12005, id FROM tag WHERE name = 'SQL优化'
    UNION ALL SELECT 12006, id FROM tag WHERE name = 'Redis'
    UNION ALL SELECT 12006, id FROM tag WHERE name = '数据结构'
    UNION ALL SELECT 12006, id FROM tag WHERE name = '缓存'
    UNION ALL SELECT 12007, id FROM tag WHERE name = 'Redis'
    UNION ALL SELECT 12007, id FROM tag WHERE name = '缓存'
    UNION ALL SELECT 12007, id FROM tag WHERE name = '高并发'
    UNION ALL SELECT 12008, id FROM tag WHERE name = 'RabbitMQ'
    UNION ALL SELECT 12008, id FROM tag WHERE name = '消息队列'
    UNION ALL SELECT 12008, id FROM tag WHERE name = '可靠性'
    UNION ALL SELECT 12009, id FROM tag WHERE name = 'Docker'
    UNION ALL SELECT 12009, id FROM tag WHERE name = '容器'
    UNION ALL SELECT 12009, id FROM tag WHERE name = '部署'
    UNION ALL SELECT 12010, id FROM tag WHERE name = 'Linux'
    UNION ALL SELECT 12010, id FROM tag WHERE name = '运维'
    UNION ALL SELECT 12010, id FROM tag WHERE name = '故障排查'
    UNION ALL SELECT 12011, id FROM tag WHERE name = 'Git'
    UNION ALL SELECT 12011, id FROM tag WHERE name = '版本控制'
    UNION ALL SELECT 12011, id FROM tag WHERE name = '团队协作'
    UNION ALL SELECT 12012, id FROM tag WHERE name = 'Vue3'
    UNION ALL SELECT 12012, id FROM tag WHERE name = '组件'
    UNION ALL SELECT 12012, id FROM tag WHERE name = '响应式'
    UNION ALL SELECT 12013, id FROM tag WHERE name = 'TypeScript'
    UNION ALL SELECT 12013, id FROM tag WHERE name = '类型系统'
    UNION ALL SELECT 12013, id FROM tag WHERE name = '前端工程'
    UNION ALL SELECT 12014, id FROM tag WHERE name = '前端'
    UNION ALL SELECT 12014, id FROM tag WHERE name = 'HTTP'
    UNION ALL SELECT 12014, id FROM tag WHERE name = '用户体验'
    UNION ALL SELECT 12015, id FROM tag WHERE name = 'Elasticsearch'
    UNION ALL SELECT 12015, id FROM tag WHERE name = '全文检索'
    UNION ALL SELECT 12015, id FROM tag WHERE name = '中文分词'
    UNION ALL SELECT 12016, id FROM tag WHERE name = 'Python'
    UNION ALL SELECT 12016, id FROM tag WHERE name = '自动化'
    UNION ALL SELECT 12016, id FROM tag WHERE name = '数据处理'
    UNION ALL SELECT 12017, id FROM tag WHERE name = '提示词'
    UNION ALL SELECT 12017, id FROM tag WHERE name = '大模型'
    UNION ALL SELECT 12017, id FROM tag WHERE name = '评测'
    UNION ALL SELECT 12018, id FROM tag WHERE name = 'RAG'
    UNION ALL SELECT 12018, id FROM tag WHERE name = '大模型'
    UNION ALL SELECT 12018, id FROM tag WHERE name = '向量检索'
    UNION ALL SELECT 12019, id FROM tag WHERE name = 'AI Agent'
    UNION ALL SELECT 12019, id FROM tag WHERE name = '工具调用'
    UNION ALL SELECT 12019, id FROM tag WHERE name = '工作流'
    UNION ALL SELECT 12020, id FROM tag WHERE name = '软件测试'
    UNION ALL SELECT 12020, id FROM tag WHERE name = '单元测试'
    UNION ALL SELECT 12020, id FROM tag WHERE name = '接口测试'
    UNION ALL SELECT 12021, id FROM tag WHERE name = '系统设计'
    UNION ALL SELECT 12021, id FROM tag WHERE name = '高可用'
    UNION ALL SELECT 12021, id FROM tag WHERE name = '架构';

    INSERT INTO search_index_task (
        course_id, generation, attempts, next_retry_at, last_error
    )
    SELECT id, 1, 0, NOW(3), NULL
    FROM course
    WHERE id IN (12001, 12002, 12003, 12004, 12005, 12006, 12007, 12008, 12009, 12010, 12011, 12012, 12013, 12014, 12015, 12016, 12017, 12018, 12019, 12020, 12021)
    ON DUPLICATE KEY UPDATE
        generation = generation + 1,
        attempts = 0,
        next_retry_at = NOW(3),
        last_error = NULL;

    COMMIT;
END$$
DELIMITER ;

CALL learnhub_seed_demo_courses_20261008();
DROP PROCEDURE IF EXISTS learnhub_seed_demo_courses_20261008;
