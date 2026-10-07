SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS learnhub DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE learnhub;

CREATE TABLE IF NOT EXISTS user (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    username VARCHAR(64) NOT NULL COMMENT '登录用户名，同一用户表内唯一',
    password_hash VARCHAR(100) NOT NULL COMMENT '用户密码的不可逆哈希值',
    nickname VARCHAR(64) NOT NULL COMMENT '用户展示昵称',
    avatar_url VARCHAR(512) COMMENT '头像访问地址',
    avatar_media_id BIGINT COMMENT '头像媒体资源编号，关联媒体资源表',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '账号状态：ACTIVE正常，DISABLED禁用',
    token_version INT NOT NULL DEFAULT 0 COMMENT '令牌版本号，递增后使旧版本登录令牌失效',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    UNIQUE KEY uk_user_username (username),
    KEY idx_user_avatar_media (avatar_media_id)
) COMMENT='用户账号表';

CREATE TABLE IF NOT EXISTS user_role (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    role_code VARCHAR(32) NOT NULL COMMENT '角色编码，如USER普通用户、ADMIN管理员',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    UNIQUE KEY uk_user_role (user_id, role_code)
) COMMENT='用户角色关联表';

CREATE TABLE IF NOT EXISTS course_category (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    name VARCHAR(64) NOT NULL COMMENT '课程分类名称',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '展示排序序号，数值越小越靠前',
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED' COMMENT '分类状态：ENABLED启用，DISABLED禁用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒'
) COMMENT='课程分类表';

CREATE TABLE IF NOT EXISTS course (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键编号',
    category_id BIGINT NOT NULL COMMENT '课程分类编号，关联课程分类表',
    title VARCHAR(160) NOT NULL COMMENT '课程标题',
    subtitle VARCHAR(255) COMMENT '课程副标题',
    cover_url VARCHAR(512) COMMENT '课程封面图片访问地址',
    description TEXT COMMENT '课程详细介绍',
    instructor VARCHAR(80) NOT NULL COMMENT '课程讲师姓名',
    price DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '课程售价，单位为人民币元',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '课程状态：DRAFT草稿、PUBLISHED已发布、OFFLINE已下架',
    like_count BIGINT NOT NULL DEFAULT 0 COMMENT '点赞或赞同数量，单位为次',
    favorite_count BIGINT NOT NULL DEFAULT 0 COMMENT '课程收藏数量，单位为次',
    student_count BIGINT NOT NULL DEFAULT 0 COMMENT '课程学习人数，单位为人',
    published_at DATETIME(3) COMMENT '课程发布时间，精确到毫秒',
    version INT NOT NULL DEFAULT 0 COMMENT '业务数据版本号，用于并发更新控制',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    KEY idx_course_status_published (status, published_at),
    KEY idx_course_category (category_id)
) COMMENT='课程信息表';

CREATE TABLE IF NOT EXISTS course_chapter (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键编号',
    course_id BIGINT NOT NULL COMMENT '课程编号，关联课程表',
    title VARCHAR(160) NOT NULL COMMENT '章节标题',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '展示排序序号，数值越小越靠前',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    KEY idx_chapter_course (course_id, sort_order)
) COMMENT='课程章节表';

CREATE TABLE IF NOT EXISTS course_lesson (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键编号',
    chapter_id BIGINT NOT NULL COMMENT '章节编号，关联课程章节表',
    title VARCHAR(160) NOT NULL COMMENT '课时标题',
    media_url VARCHAR(512) COMMENT '课时媒体播放地址',
    media_asset_id BIGINT COMMENT '课时媒体资源编号，关联媒体资源表',
    duration_seconds INT NOT NULL DEFAULT 0 COMMENT '课时时长，单位为秒',
    free_preview TINYINT NOT NULL DEFAULT 0 COMMENT '免费试看标记：0不可免费试看，1可免费试看',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '展示排序序号，数值越小越靠前',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    KEY idx_lesson_chapter (chapter_id, sort_order),
    KEY idx_lesson_media_asset (media_asset_id)
) COMMENT='课程课时表';

CREATE TABLE IF NOT EXISTS course_order (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    order_no VARCHAR(64) NOT NULL COMMENT '课程订单业务编号，全局唯一',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    course_id BIGINT NOT NULL COMMENT '课程编号，关联课程表',
    course_title VARCHAR(160) NOT NULL COMMENT '下单时的课程标题快照',
    original_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '订单原始金额，单位为人民币元',
    paid_amount DECIMAL(12,2) NULL COMMENT '订单实付金额，单位为人民币元；未支付时为空',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '课程订单状态：PENDING待支付，PAID已支付',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    paid_at DATETIME(3) NULL COMMENT '订单支付完成时间，精确到毫秒',
    UNIQUE KEY uk_course_order_no (order_no),
    UNIQUE KEY uk_course_order_user_course (user_id, course_id),
    KEY idx_course_order_course_status (course_id, status)
) COMMENT='课程购买订单表';

CREATE TABLE IF NOT EXISTS learning_progress (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    lesson_id BIGINT NOT NULL COMMENT '课时编号，关联课程课时表',
    position_seconds INT NOT NULL DEFAULT 0 COMMENT '课时最近播放位置，单位为秒',
    completed TINYINT NOT NULL DEFAULT 0 COMMENT '课时完成标记：0未完成，1已完成',
    last_learned_at DATETIME(3) NOT NULL COMMENT '最近学习时间，精确到毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_progress_user_lesson (user_id, lesson_id)
) COMMENT='用户课时学习进度表';

CREATE TABLE IF NOT EXISTS tag (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    name VARCHAR(64) NOT NULL COMMENT '课程标签名称，同一标签表内唯一',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    UNIQUE KEY uk_tag_name (name)
) COMMENT='课程标签表';

CREATE TABLE IF NOT EXISTS course_tag (
    course_id BIGINT NOT NULL COMMENT '课程编号，关联课程表',
    tag_id BIGINT NOT NULL COMMENT '标签编号，关联标签表',
    PRIMARY KEY (course_id, tag_id)
) COMMENT='课程标签关联表';

CREATE TABLE IF NOT EXISTS favorite_record (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    target_type VARCHAR(20) NOT NULL COMMENT '互动目标类型：COURSE课程、QUESTION问题、ANSWER回答',
    target_id BIGINT NOT NULL COMMENT '互动目标编号，由目标类型确定关联表',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    UNIQUE KEY uk_favorite_user_target (user_id, target_type, target_id)
) COMMENT='用户收藏记录表';

CREATE TABLE IF NOT EXISTS like_record (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    target_type VARCHAR(20) NOT NULL COMMENT '互动目标类型：COURSE课程、QUESTION问题、ANSWER回答',
    target_id BIGINT NOT NULL COMMENT '互动目标编号，由目标类型确定关联表',
    active TINYINT NOT NULL DEFAULT 1 COMMENT '点赞关系有效标记：0已取消，1有效点赞',
    event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '点赞事件单调序列，用于拒绝旧事件覆盖新状态',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_like_user_target (user_id, target_type, target_id),
    KEY idx_like_target_active (target_type, target_id, active)
) COMMENT='用户点赞关系表';

CREATE TABLE IF NOT EXISTS like_sync_batch (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    batch_id VARCHAR(64) NOT NULL COMMENT '点赞同步批次唯一编号，用于刷库幂等',
    status VARCHAR(20) NOT NULL COMMENT '同步批次状态：PROCESSING处理中，SUCCESS已完成',
    relation_count INT NOT NULL DEFAULT 0 COMMENT '当前批次同步的用户目标关系数量',
    target_count INT NOT NULL DEFAULT 0 COMMENT '当前批次同步的互动目标数量',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_like_sync_batch (batch_id)
) COMMENT='点赞批量同步幂等记录表';

CREATE TABLE IF NOT EXISTS like_event_inbox (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    event_id VARCHAR(64) NOT NULL COMMENT '点赞事件全局唯一编号，用于消息幂等',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    target_type VARCHAR(20) NOT NULL COMMENT '互动目标类型：COURSE课程、QUESTION问题、ANSWER回答',
    target_id BIGINT NOT NULL COMMENT '互动目标编号，由目标类型确定关联表',
    liked TINYINT NOT NULL COMMENT '点赞事件期望状态：0取消点赞，1点赞',
    event_sequence BIGINT NOT NULL COMMENT '点赞事件单调序列，用于拒绝旧事件覆盖新状态',
    event_version INT NOT NULL COMMENT '点赞事件协议版本号',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '点赞事件处理状态：PENDING待同步，PROCESSED已同步',
    occurred_at DATETIME(3) NOT NULL COMMENT '点赞事件发生时间，精确到毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    processed_at DATETIME(3) NULL COMMENT '点赞事件完成数据库同步的时间，未处理时为空',
    UNIQUE KEY uk_like_event_inbox_event (event_id),
    KEY idx_like_event_inbox_status (status, id)
) COMMENT='点赞事件持久化收件箱表';

CREATE TABLE IF NOT EXISTS question (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    course_id BIGINT COMMENT '课程编号，关联课程表',
    title VARCHAR(200) NOT NULL COMMENT '问题标题',
    content TEXT NOT NULL COMMENT '问题详细描述',
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN' COMMENT '问题状态：OPEN公开提问；其他状态不进入公开查询',
    like_count BIGINT NOT NULL DEFAULT 0 COMMENT '点赞或赞同数量，单位为次',
    answer_count INT NOT NULL DEFAULT 0 COMMENT '问题回答数量，单位为条',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    KEY idx_question_course_created (course_id, created_at)
) COMMENT='问答社区问题表';

CREATE TABLE IF NOT EXISTS answer (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    question_id BIGINT NOT NULL COMMENT '问题编号，关联问答问题表',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    content TEXT NOT NULL COMMENT '回答正文内容',
    status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE' COMMENT '回答状态：VISIBLE公开可见；其他状态不进入公开查询',
    like_count BIGINT NOT NULL DEFAULT 0 COMMENT '点赞或赞同数量，单位为次',
    accepted TINYINT NOT NULL DEFAULT 0 COMMENT '回答采纳标记：0未采纳，1已采纳',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    KEY idx_answer_question_created (question_id, created_at)
) COMMENT='问答社区回答表';

CREATE TABLE IF NOT EXISTS coupon (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    name VARCHAR(120) NOT NULL COMMENT '优惠券活动名称',
    type VARCHAR(20) NOT NULL COMMENT '优惠券类型：NORMAL普通券，SECKILL秒杀券',
    threshold_amount DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '优惠券最低使用金额门槛，单位为人民币元；0表示无门槛',
    discount_amount DECIMAL(10,2) NOT NULL COMMENT '优惠券抵扣金额，单位为人民币元',
    total_stock INT NOT NULL COMMENT '优惠券活动初始总库存，单位为张',
    available_stock INT NOT NULL COMMENT '优惠券数据库剩余可领取库存，单位为张',
    claim_start_at DATETIME(3) NOT NULL COMMENT '优惠券领取开始时间，包含该时刻',
    claim_end_at DATETIME(3) NOT NULL COMMENT '优惠券领取截止时间，不包含该时刻',
    use_start_at DATETIME(3) NOT NULL COMMENT '优惠券可使用开始时间',
    use_end_at DATETIME(3) NOT NULL COMMENT '优惠券使用截止时间，到期后不可使用',
    per_user_limit INT NOT NULL DEFAULT 1 COMMENT '每个用户的领取数量上限，单位为张',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT '优惠券活动状态：DRAFT草稿、ACTIVE启用；是否可领取还需校验时间和库存',
    version INT NOT NULL DEFAULT 0 COMMENT '业务数据版本号，用于并发更新控制',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒'
) COMMENT='优惠券活动表';

CREATE TABLE IF NOT EXISTS coupon_claim (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    coupon_id BIGINT NOT NULL COMMENT '优惠券活动编号，关联优惠券表',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT '领取券状态：AVAILABLE可用、USED已使用、EXPIRED已过期；到期状态可按有效期计算',
    claimed_at DATETIME(3) NOT NULL COMMENT '优惠券领取时间，精确到毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_claim_coupon_user (coupon_id, user_id)
) COMMENT='用户优惠券领取记录表';

CREATE TABLE IF NOT EXISTS seckill_order (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    request_id VARCHAR(64) NOT NULL COMMENT '秒杀请求全局唯一编号，同时用作消息编号',
    coupon_id BIGINT NOT NULL COMMENT '优惠券活动编号，关联优惠券表',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '秒杀请求状态：PENDING待确认、SUCCESS领取成功、FAILED领取失败',
    publish_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '消息发布状态：PENDING待发布、PUBLISHING发布中、SENT已发送、FAILED发布失败',
    publish_attempts INT NOT NULL DEFAULT 0 COMMENT '消息累计发布尝试次数',
    compensated TINYINT NOT NULL DEFAULT 0 COMMENT '库存补偿完成标记：0未完成，1已完成',
    failure_reason VARCHAR(255) COMMENT '秒杀或消息发布失败原因摘要',
    reserved_at DATETIME(3) NOT NULL COMMENT 'Redis库存预留及请求受理时间，精确到毫秒',
    next_retry_at DATETIME(3) COMMENT '发布重试或消费确认超时后的下次处理时间',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_seckill_request (request_id),
    UNIQUE KEY uk_seckill_coupon_user (coupon_id, user_id),
    KEY idx_seckill_recovery (status, compensated, next_retry_at)
) COMMENT='优惠券秒杀请求及可靠发布状态表';

CREATE TABLE IF NOT EXISTS mq_consume_log (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    message_id VARCHAR(64) NOT NULL COMMENT '消费消息唯一编号，用于消息去重',
    consumer_name VARCHAR(80) NOT NULL COMMENT '消息消费者名称，与消息编号共同保证幂等',
    status VARCHAR(20) NOT NULL COMMENT '消息消费结果状态，如SUCCESS消费成功',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_message_consumer (message_id, consumer_name)
) COMMENT='消息消费幂等日志表';

CREATE TABLE IF NOT EXISTS media_asset (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    owner_id BIGINT NOT NULL COMMENT '媒体资源所属用户编号，关联用户表',
    provider VARCHAR(32) NOT NULL COMMENT '媒体存储服务提供商编码，如ALIYUN_OSS',
    bucket_name VARCHAR(128) NOT NULL COMMENT '对象存储桶名称',
    object_key VARCHAR(512) NOT NULL COMMENT '对象存储文件键，在资源表内唯一',
    original_name VARCHAR(255) NOT NULL COMMENT '上传文件原始名称',
    content_type VARCHAR(128) NOT NULL COMMENT '文件媒体类型，即MIME类型',
    file_size BIGINT NOT NULL COMMENT '文件大小，单位为字节',
    etag VARCHAR(128) COMMENT '对象存储返回的文件实体标识',
    status VARCHAR(20) NOT NULL COMMENT '媒体资源状态：UPLOADING上传中、READY可用、FAILED失败',
    biz_type VARCHAR(32) NOT NULL COMMENT '媒体关联业务类型，如AVATAR头像、COURSE_LESSON课时视频',
    biz_id BIGINT COMMENT '媒体关联业务记录编号，可为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    UNIQUE KEY uk_media_object_key (object_key),
    KEY idx_media_owner_created (owner_id, created_at),
    KEY idx_media_biz (biz_type, biz_id)
) COMMENT='媒体文件资源表';
