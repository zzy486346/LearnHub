-- 为现有 LearnHub 表和字段补齐中文注释，可重复执行。
-- 字段定义读取自 information_schema，保留各环境实际类型、默认值和其他属性。
-- 不修改业务数据；已有中文注释保留。执行前建议备份表结构。
SET NAMES utf8mb4;
USE learnhub;
CREATE TEMPORARY TABLE learnhub_comment_specs (
    table_name VARCHAR(64) NOT NULL COMMENT '业务表名',
    column_name VARCHAR(64) NOT NULL COMMENT '业务字段名，空字符串表示表注释',
    chinese_comment VARCHAR(512) NOT NULL COMMENT '待补充的中文注释',
    PRIMARY KEY (table_name, column_name)
) COMMENT='中文数据库注释临时映射表';
INSERT INTO learnhub_comment_specs VALUES
    ('user', '', '用户账号表'),
    ('user', 'id', '主键编号'),
    ('user', 'username', '登录用户名，同一用户表内唯一'),
    ('user', 'password_hash', '用户密码的不可逆哈希值'),
    ('user', 'nickname', '用户展示昵称'),
    ('user', 'avatar_url', '头像访问地址'),
    ('user', 'avatar_media_id', '头像媒体资源编号，关联媒体资源表'),
    ('user', 'status', '账号状态：ACTIVE正常，DISABLED禁用'),
    ('user', 'token_version', '令牌版本号，递增后使旧版本登录令牌失效'),
    ('user', 'created_at', '创建时间，精确到毫秒'),
    ('user', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('user', 'deleted', '逻辑删除标记：0正常，1已删除'),
    ('user_role', '', '用户角色关联表'),
    ('user_role', 'id', '主键编号'),
    ('user_role', 'user_id', '用户编号，关联用户表'),
    ('user_role', 'role_code', '角色编码，如USER普通用户、ADMIN管理员'),
    ('user_role', 'created_at', '创建时间，精确到毫秒'),
    ('course_category', '', '课程分类表'),
    ('course_category', 'id', '主键编号'),
    ('course_category', 'name', '课程分类名称'),
    ('course_category', 'sort_order', '展示排序序号，数值越小越靠前'),
    ('course_category', 'status', '分类状态：ENABLED启用，DISABLED禁用'),
    ('course_category', 'created_at', '创建时间，精确到毫秒'),
    ('course_category', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('course', '', '课程信息表'),
    ('course', 'id', '主键编号'),
    ('course', 'category_id', '课程分类编号，关联课程分类表'),
    ('course', 'title', '课程标题'),
    ('course', 'subtitle', '课程副标题'),
    ('course', 'cover_url', '课程封面图片访问地址'),
    ('course', 'description', '课程详细介绍'),
    ('course', 'instructor', '课程讲师姓名'),
    ('course', 'price', '课程售价，单位为人民币元'),
    ('course', 'status', '课程状态：DRAFT草稿、PUBLISHED已发布、OFFLINE已下架'),
    ('course', 'like_count', '点赞或赞同数量，单位为次'),
    ('course', 'favorite_count', '课程收藏数量，单位为次'),
    ('course', 'student_count', '课程学习人数，单位为人'),
    ('course', 'published_at', '课程发布时间，精确到毫秒'),
    ('course', 'version', '业务数据版本号，用于并发更新控制'),
    ('course', 'created_at', '创建时间，精确到毫秒'),
    ('course', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('course', 'deleted', '逻辑删除标记：0正常，1已删除'),
    ('course_chapter', '', '课程章节表'),
    ('course_chapter', 'id', '主键编号'),
    ('course_chapter', 'course_id', '课程编号，关联课程表'),
    ('course_chapter', 'title', '章节标题'),
    ('course_chapter', 'sort_order', '展示排序序号，数值越小越靠前'),
    ('course_chapter', 'created_at', '创建时间，精确到毫秒'),
    ('course_chapter', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('course_lesson', '', '课程课时表'),
    ('course_lesson', 'id', '主键编号'),
    ('course_lesson', 'chapter_id', '章节编号，关联课程章节表'),
    ('course_lesson', 'title', '课时标题'),
    ('course_lesson', 'media_url', '课时媒体播放地址'),
    ('course_lesson', 'media_asset_id', '课时媒体资源编号，关联媒体资源表'),
    ('course_lesson', 'duration_seconds', '课时时长，单位为秒'),
    ('course_lesson', 'free_preview', '免费试看标记：0不可免费试看，1可免费试看'),
    ('course_lesson', 'sort_order', '展示排序序号，数值越小越靠前'),
    ('course_lesson', 'created_at', '创建时间，精确到毫秒'),
    ('course_lesson', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('course_order', '', '课程购买订单表'),
    ('course_order', 'id', '主键编号'),
    ('course_order', 'order_no', '课程订单业务编号，全局唯一'),
    ('course_order', 'user_id', '用户编号，关联用户表'),
    ('course_order', 'course_id', '课程编号，关联课程表'),
    ('course_order', 'course_title', '下单时的课程标题快照'),
    ('course_order', 'original_amount', '订单原始金额，单位为人民币元'),
    ('course_order', 'paid_amount', '订单实付金额，单位为人民币元；未支付时为空'),
    ('course_order', 'status', '课程订单状态：PENDING待支付，PAID已支付'),
    ('course_order', 'created_at', '创建时间，精确到毫秒'),
    ('course_order', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('course_order', 'paid_at', '订单支付完成时间，精确到毫秒'),
    ('learning_progress', '', '用户课时学习进度表'),
    ('learning_progress', 'id', '主键编号'),
    ('learning_progress', 'user_id', '用户编号，关联用户表'),
    ('learning_progress', 'lesson_id', '课时编号，关联课程课时表'),
    ('learning_progress', 'position_seconds', '课时最近播放位置，单位为秒'),
    ('learning_progress', 'completed', '课时完成标记：0未完成，1已完成'),
    ('learning_progress', 'last_learned_at', '最近学习时间，精确到毫秒'),
    ('learning_progress', 'created_at', '创建时间，精确到毫秒'),
    ('learning_progress', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('tag', '', '课程标签表'),
    ('tag', 'id', '主键编号'),
    ('tag', 'name', '课程标签名称，同一标签表内唯一'),
    ('tag', 'created_at', '创建时间，精确到毫秒'),
    ('course_tag', '', '课程标签关联表'),
    ('course_tag', 'course_id', '课程编号，关联课程表'),
    ('course_tag', 'tag_id', '标签编号，关联标签表'),
    ('favorite_record', '', '用户收藏记录表'),
    ('favorite_record', 'id', '主键编号'),
    ('favorite_record', 'user_id', '用户编号，关联用户表'),
    ('favorite_record', 'target_type', '互动目标类型：COURSE课程、QUESTION问题、ANSWER回答'),
    ('favorite_record', 'target_id', '互动目标编号，由目标类型确定关联表'),
    ('favorite_record', 'created_at', '创建时间，精确到毫秒'),
    ('like_record', '', '用户点赞关系表'),
    ('like_record', 'id', '主键编号'),
    ('like_record', 'user_id', '用户编号，关联用户表'),
    ('like_record', 'target_type', '互动目标类型：COURSE课程、QUESTION问题、ANSWER回答'),
    ('like_record', 'target_id', '互动目标编号，由目标类型确定关联表'),
    ('like_record', 'active', '点赞关系有效标记：0已取消，1有效点赞'),
    ('like_record', 'event_sequence', '点赞事件单调序列，用于拒绝旧事件覆盖新状态'),
    ('like_record', 'created_at', '创建时间，精确到毫秒'),
    ('like_record', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('like_sync_batch', '', '点赞批量同步幂等记录表'),
    ('like_sync_batch', 'id', '主键编号'),
    ('like_sync_batch', 'batch_id', '点赞同步批次唯一编号，用于刷库幂等'),
    ('like_sync_batch', 'status', '同步批次状态：PROCESSING处理中，SUCCESS已完成'),
    ('like_sync_batch', 'relation_count', '当前批次同步的用户目标关系数量'),
    ('like_sync_batch', 'target_count', '当前批次同步的互动目标数量'),
    ('like_sync_batch', 'created_at', '创建时间，精确到毫秒'),
    ('like_sync_batch', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('like_event_inbox', '', '点赞事件持久化收件箱表'),
    ('like_event_inbox', 'id', '主键编号'),
    ('like_event_inbox', 'event_id', '点赞事件全局唯一编号，用于消息幂等'),
    ('like_event_inbox', 'user_id', '用户编号，关联用户表'),
    ('like_event_inbox', 'target_type', '互动目标类型：COURSE课程、QUESTION问题、ANSWER回答'),
    ('like_event_inbox', 'target_id', '互动目标编号，由目标类型确定关联表'),
    ('like_event_inbox', 'liked', '点赞事件期望状态：0取消点赞，1点赞'),
    ('like_event_inbox', 'event_sequence', '点赞事件单调序列，用于拒绝旧事件覆盖新状态'),
    ('like_event_inbox', 'event_version', '点赞事件协议版本号'),
    ('like_event_inbox', 'status', '点赞事件处理状态：PENDING待同步，PROCESSED已同步'),
    ('like_event_inbox', 'occurred_at', '点赞事件发生时间，精确到毫秒'),
    ('like_event_inbox', 'created_at', '创建时间，精确到毫秒'),
    ('like_event_inbox', 'processed_at', '点赞事件完成数据库同步的时间，未处理时为空'),
    ('question', '', '问答社区问题表'),
    ('question', 'id', '主键编号'),
    ('question', 'user_id', '用户编号，关联用户表'),
    ('question', 'course_id', '课程编号，关联课程表'),
    ('question', 'title', '问题标题'),
    ('question', 'content', '问题详细描述'),
    ('question', 'status', '问题状态：OPEN公开提问；其他状态不进入公开查询'),
    ('question', 'like_count', '点赞或赞同数量，单位为次'),
    ('question', 'answer_count', '问题回答数量，单位为条'),
    ('question', 'created_at', '创建时间，精确到毫秒'),
    ('question', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('question', 'deleted', '逻辑删除标记：0正常，1已删除'),
    ('answer', '', '问答社区回答表'),
    ('answer', 'id', '主键编号'),
    ('answer', 'question_id', '问题编号，关联问答问题表'),
    ('answer', 'user_id', '用户编号，关联用户表'),
    ('answer', 'content', '回答正文内容'),
    ('answer', 'status', '回答状态：VISIBLE公开可见；其他状态不进入公开查询'),
    ('answer', 'like_count', '点赞或赞同数量，单位为次'),
    ('answer', 'accepted', '回答采纳标记：0未采纳，1已采纳'),
    ('answer', 'created_at', '创建时间，精确到毫秒'),
    ('answer', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('answer', 'deleted', '逻辑删除标记：0正常，1已删除'),
    ('coupon', '', '优惠券活动表'),
    ('coupon', 'id', '主键编号'),
    ('coupon', 'name', '优惠券活动名称'),
    ('coupon', 'type', '优惠券类型：NORMAL普通券，SECKILL秒杀券'),
    ('coupon', 'threshold_amount', '优惠券最低使用金额门槛，单位为人民币元；0表示无门槛'),
    ('coupon', 'discount_amount', '优惠券抵扣金额，单位为人民币元'),
    ('coupon', 'total_stock', '优惠券活动初始总库存，单位为张'),
    ('coupon', 'available_stock', '优惠券数据库剩余可领取库存，单位为张'),
    ('coupon', 'claim_start_at', '优惠券领取开始时间，包含该时刻'),
    ('coupon', 'claim_end_at', '优惠券领取截止时间，不包含该时刻'),
    ('coupon', 'use_start_at', '优惠券可使用开始时间'),
    ('coupon', 'use_end_at', '优惠券使用截止时间，到期后不可使用'),
    ('coupon', 'per_user_limit', '每个用户的领取数量上限，单位为张'),
    ('coupon', 'status', '优惠券活动状态：DRAFT草稿、ACTIVE启用；是否可领取还需校验时间和库存'),
    ('coupon', 'version', '业务数据版本号，用于并发更新控制'),
    ('coupon', 'created_at', '创建时间，精确到毫秒'),
    ('coupon', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('coupon_claim', '', '用户优惠券领取记录表'),
    ('coupon_claim', 'id', '主键编号'),
    ('coupon_claim', 'coupon_id', '优惠券活动编号，关联优惠券表'),
    ('coupon_claim', 'user_id', '用户编号，关联用户表'),
    ('coupon_claim', 'status', '领取券状态：AVAILABLE可用、USED已使用、EXPIRED已过期；到期状态可按有效期计算'),
    ('coupon_claim', 'claimed_at', '优惠券领取时间，精确到毫秒'),
    ('coupon_claim', 'created_at', '创建时间，精确到毫秒'),
    ('coupon_claim', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('seckill_order', '', '优惠券秒杀请求及可靠发布状态表'),
    ('seckill_order', 'id', '主键编号'),
    ('seckill_order', 'request_id', '秒杀请求全局唯一编号，同时用作消息编号'),
    ('seckill_order', 'coupon_id', '优惠券活动编号，关联优惠券表'),
    ('seckill_order', 'user_id', '用户编号，关联用户表'),
    ('seckill_order', 'status', '秒杀请求状态：PENDING待确认、SUCCESS领取成功、FAILED领取失败'),
    ('seckill_order', 'publish_status', '消息发布状态：PENDING待发布、PUBLISHING发布中、SENT已发送、FAILED发布失败'),
    ('seckill_order', 'publish_attempts', '消息累计发布尝试次数'),
    ('seckill_order', 'compensated', '库存补偿完成标记：0未完成，1已完成'),
    ('seckill_order', 'failure_reason', '秒杀或消息发布失败原因摘要'),
    ('seckill_order', 'reserved_at', 'Redis库存预留及请求受理时间，精确到毫秒'),
    ('seckill_order', 'next_retry_at', '发布重试或消费确认超时后的下次处理时间'),
    ('seckill_order', 'created_at', '创建时间，精确到毫秒'),
    ('seckill_order', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('mq_consume_log', '', '消息消费幂等日志表'),
    ('mq_consume_log', 'id', '主键编号'),
    ('mq_consume_log', 'message_id', '消费消息唯一编号，用于消息去重'),
    ('mq_consume_log', 'consumer_name', '消息消费者名称，与消息编号共同保证幂等'),
    ('mq_consume_log', 'status', '消息消费结果状态，如SUCCESS消费成功'),
    ('mq_consume_log', 'created_at', '创建时间，精确到毫秒'),
    ('mq_consume_log', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('media_asset', '', '媒体文件资源表'),
    ('media_asset', 'id', '主键编号'),
    ('media_asset', 'owner_id', '媒体资源所属用户编号，关联用户表'),
    ('media_asset', 'provider', '媒体存储服务提供商编码，如ALIYUN_OSS'),
    ('media_asset', 'bucket_name', '对象存储桶名称'),
    ('media_asset', 'object_key', '对象存储文件键，在资源表内唯一'),
    ('media_asset', 'original_name', '上传文件原始名称'),
    ('media_asset', 'content_type', '文件媒体类型，即MIME类型'),
    ('media_asset', 'file_size', '文件大小，单位为字节'),
    ('media_asset', 'etag', '对象存储返回的文件实体标识'),
    ('media_asset', 'status', '媒体资源状态：UPLOADING上传中、READY可用、FAILED失败'),
    ('media_asset', 'biz_type', '媒体关联业务类型，如AVATAR头像、COURSE_LESSON课时视频'),
    ('media_asset', 'biz_id', '媒体关联业务记录编号，可为空'),
    ('media_asset', 'created_at', '创建时间，精确到毫秒'),
    ('media_asset', 'updated_at', '更新时间，记录修改时自动更新，精确到毫秒'),
    ('media_asset', 'deleted', '逻辑删除标记：0正常，1已删除');

DELIMITER $$
CREATE PROCEDURE learnhub_comment_v20261007()
BEGIN
    DECLARE finished INT DEFAULT 0;
    DECLARE table_name_value VARCHAR(64);
    DECLARE column_name_value VARCHAR(64);
    DECLARE definition_value TEXT;
    DECLARE current_table VARCHAR(64) DEFAULT '';
    DECLARE table_comment_value VARCHAR(512);
    DECLARE table_comment_existing TEXT;
    DECLARE statement_value LONGTEXT DEFAULT '';
    DECLARE column_cursor CURSOR FOR
        SELECT c.TABLE_NAME, c.COLUMN_NAME,
               CONCAT('MODIFY COLUMN `', c.COLUMN_NAME, '` ', c.COLUMN_TYPE,
                 IF(c.CHARACTER_SET_NAME IS NULL, '', CONCAT(' CHARACTER SET ', c.CHARACTER_SET_NAME, ' COLLATE ', c.COLLATION_NAME)),
                 IF(c.IS_NULLABLE = 'NO', ' NOT NULL', ' NULL'),
                 CASE WHEN c.COLUMN_DEFAULT IS NULL THEN IF(c.IS_NULLABLE = 'YES', ' DEFAULT NULL', '')
                      WHEN c.EXTRA LIKE '%DEFAULT_GENERATED%' THEN CONCAT(' DEFAULT ', c.COLUMN_DEFAULT)
                      ELSE CONCAT(' DEFAULT ', QUOTE(c.COLUMN_DEFAULT)) END,
                 IF(TRIM(REPLACE(c.EXTRA, 'DEFAULT_GENERATED', '')) = '', '', CONCAT(' ', TRIM(REPLACE(c.EXTRA, 'DEFAULT_GENERATED', '')))),
                 ' COMMENT ', QUOTE(s.chinese_comment))
        FROM information_schema.COLUMNS c
        JOIN learnhub_comment_specs s ON s.table_name = c.TABLE_NAME AND s.column_name = c.COLUMN_NAME
        WHERE c.TABLE_SCHEMA = DATABASE() AND c.COLUMN_COMMENT NOT REGEXP '[一-龥]'
        ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION;
    DECLARE table_cursor CURSOR FOR
        SELECT t.TABLE_NAME, s.chinese_comment FROM information_schema.TABLES t
        JOIN learnhub_comment_specs s ON s.table_name = t.TABLE_NAME AND s.column_name = ''
        WHERE t.TABLE_SCHEMA = DATABASE() AND t.TABLE_COMMENT NOT REGEXP '[一-龥]';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished = 1;
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS c
               WHERE c.TABLE_SCHEMA = DATABASE() AND c.COLUMN_COMMENT NOT REGEXP '[一-龥]'
                 AND (c.GENERATION_EXPRESSION <> '' OR NOT EXISTS (
                   SELECT 1 FROM learnhub_comment_specs s WHERE s.table_name = c.TABLE_NAME AND s.column_name = c.COLUMN_NAME))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '字段注释映射不完整或存在生成列，请先核对结构';
    END IF;
    OPEN column_cursor;
    comment_loop: LOOP
        FETCH column_cursor INTO table_name_value, column_name_value, definition_value;
        IF finished = 1 THEN LEAVE comment_loop; END IF;
        IF current_table <> table_name_value THEN
            IF current_table <> '' THEN
                SET @learnhub_comment_sql = statement_value;
                PREPARE comment_stmt FROM @learnhub_comment_sql;
                EXECUTE comment_stmt;
                DEALLOCATE PREPARE comment_stmt;
            END IF;
            SET current_table = table_name_value;
            SELECT s.chinese_comment, t.TABLE_COMMENT INTO table_comment_value, table_comment_existing
            FROM learnhub_comment_specs s JOIN information_schema.TABLES t ON t.TABLE_NAME = s.table_name
            WHERE s.table_name = current_table AND s.column_name = '' AND t.TABLE_SCHEMA = DATABASE();
            SET statement_value = CONCAT('ALTER TABLE `', current_table, '` COMMENT = ',
                QUOTE(IF(table_comment_existing REGEXP '[一-龥]', table_comment_existing, table_comment_value)));
        END IF;
        SET statement_value = CONCAT(statement_value, ', ', definition_value);
    END LOOP;
    CLOSE column_cursor;
    IF current_table <> '' THEN
        SET @learnhub_comment_sql = statement_value;
        PREPARE comment_stmt FROM @learnhub_comment_sql;
        EXECUTE comment_stmt;
        DEALLOCATE PREPARE comment_stmt;
    END IF;
    SET finished = 0;
    OPEN table_cursor;
    table_loop: LOOP
        FETCH table_cursor INTO table_name_value, table_comment_value;
        IF finished = 1 THEN LEAVE table_loop; END IF;
        SET @learnhub_comment_sql = CONCAT('ALTER TABLE `', table_name_value, '` COMMENT = ', QUOTE(table_comment_value));
        PREPARE comment_stmt FROM @learnhub_comment_sql;
        EXECUTE comment_stmt;
        DEALLOCATE PREPARE comment_stmt;
    END LOOP;
    CLOSE table_cursor;
END$$
DELIMITER ;
CALL learnhub_comment_v20261007();
DROP PROCEDURE learnhub_comment_v20261007;
DROP TEMPORARY TABLE learnhub_comment_specs;

-- 验收：以下两项均应为0。
SELECT COUNT(*) AS tables_without_chinese_comments FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'learnhub' AND TABLE_TYPE = 'BASE TABLE' AND TABLE_COMMENT NOT REGEXP '[一-龥]';
SELECT COUNT(*) AS columns_without_chinese_comments FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'learnhub' AND COLUMN_COMMENT NOT REGEXP '[一-龥]';
-- 回滚：本脚本只增加说明信息，无业务回滚要求；如需恢复旧注释，使用执行前的结构备份。
