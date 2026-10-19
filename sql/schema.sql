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
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_course_category_name (name)
) COMMENT='课程分类表';

CREATE TABLE IF NOT EXISTS course (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键编号',
    category_id BIGINT NOT NULL COMMENT '课程分类编号，关联课程分类表',
    title VARCHAR(160) NOT NULL COMMENT '课程标题',
    subtitle VARCHAR(255) COMMENT '课程副标题',
    cover_url VARCHAR(512) COMMENT '课程封面图片访问地址',
    cover_media_id BIGINT COMMENT '课程封面媒体资源编号，关联媒体资源表',
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
    KEY idx_course_category (category_id),
    KEY idx_course_cover_media (cover_media_id)
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
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
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
    coupon_claim_id BIGINT NULL COMMENT '订单使用的用户优惠券领取记录编号',
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '订单优惠抵扣金额，单位为人民币元',
    payable_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '订单应付金额，单位为人民币元',
    paid_amount DECIMAL(12,2) NULL COMMENT '订单实付金额，单位为人民币元；未支付时为空',
    payment_mode VARCHAR(24) NOT NULL DEFAULT 'DISABLED' COMMENT '订单支付模式：DISABLED未开放、LOCAL_DEMO本机演示、LEGACY_DEMO历史演示',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '课程订单状态：PENDING待支付、PAID已支付、CANCELLED已取消、CLOSED超时关闭、REFUNDED已退款',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    paid_at DATETIME(3) NULL COMMENT '订单支付完成时间，精确到毫秒',
    expires_at DATETIME(3) NULL COMMENT '待支付订单失效时间，精确到毫秒',
    cancelled_at DATETIME(3) NULL COMMENT '订单主动取消或超时关闭时间，精确到毫秒',
    refunded_at DATETIME(3) NULL COMMENT '演示订单退款完成时间，精确到毫秒',
    UNIQUE KEY uk_course_order_no (order_no),
    UNIQUE KEY uk_course_order_user_course (user_id, course_id),
    KEY idx_course_order_coupon_claim (coupon_claim_id),
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
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_tag_name (name)
) COMMENT='课程标签表';

CREATE TABLE IF NOT EXISTS course_tag (
    course_id BIGINT NOT NULL COMMENT '课程编号，关联课程表',
    tag_id BIGINT NOT NULL COMMENT '标签编号，关联标签表',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    PRIMARY KEY (course_id, tag_id)
) COMMENT='课程标签关联表';

CREATE TABLE IF NOT EXISTS search_index_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键编号',
    course_id BIGINT NOT NULL COMMENT '待同步的课程编号，关联课程表',
    generation BIGINT NOT NULL DEFAULT 1 COMMENT '任务代次，每次课程变更时递增，用于完成和失败更新的并发校验',
    attempts INT NOT NULL DEFAULT 0 COMMENT '当前代次已失败的同步尝试次数',
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次允许执行索引同步的时间，精确到毫秒',
    last_error VARCHAR(1000) NULL COMMENT '最近一次索引同步失败的错误摘要，成功或新代次时为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_search_index_task_course (course_id),
    KEY idx_search_index_task_due (next_retry_at, id)
) COMMENT='课程搜索索引同步任务表';

CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识文档主键编号',
    source_key VARCHAR(191) NOT NULL COMMENT '外部知识来源稳定标识，同一来源全局唯一',
    title VARCHAR(160) NOT NULL COMMENT '知识文档标题',
    course_id BIGINT NOT NULL COMMENT '绑定课程编号，关联课程信息表',
    lesson_id BIGINT NOT NULL COMMENT '绑定课时编号，关联课程课时表',
    visibility VARCHAR(32) NOT NULL DEFAULT 'COURSE_LESSON' COMMENT '正文可见范围：COURSE_LESSON按绑定课程和课时鉴权',
    content_hash CHAR(64) NOT NULL COMMENT '标题、归属和场景正文的SHA-256摘要，用于幂等导入',
    source_version BIGINT NOT NULL DEFAULT 1 COMMENT '知识来源版本号，内容变化时由服务端递增',
    indexed_version BIGINT NULL COMMENT '已完整写入并刷新知识索引的来源版本号',
    index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '索引状态：PENDING待同步、INDEXED已同步、FAILED同步失败',
    attempts INT NOT NULL DEFAULT 0 COMMENT '当前版本索引同步失败次数',
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '当前版本下次允许重试索引的时间，精确到毫秒',
    last_error VARCHAR(1000) NULL COMMENT '当前版本最近一次索引同步失败摘要',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    UNIQUE KEY uk_knowledge_document_source (source_key),
    KEY idx_knowledge_document_due (index_status, next_retry_at, id),
    KEY idx_knowledge_document_lesson (course_id, lesson_id)
) COMMENT='课程课时知识文档表';

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识片段主键编号',
    document_id BIGINT NOT NULL COMMENT '所属知识文档编号，关联知识文档表',
    source_version BIGINT NOT NULL COMMENT '片段所属知识来源版本号',
    chunk_order INT NOT NULL COMMENT '片段在当前文档版本内的顺序，从零开始',
    heading VARCHAR(255) NULL COMMENT '片段所属场景标题',
    content TEXT NOT NULL COMMENT '知识片段正文，最多六百个Unicode代码点',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_knowledge_chunk_order (document_id, source_version, chunk_order),
    KEY idx_knowledge_chunk_version (document_id, source_version, id)
) COMMENT='知识文档版本片段表';

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
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT '领取券状态：AVAILABLE可用、RESERVED订单占用、USED已核销、EXPIRED已过期；到期状态可按有效期计算',
    reserved_order_id BIGINT NULL COMMENT '当前占用该券的待支付课程订单编号',
    used_order_id BIGINT NULL COMMENT '核销该券的已支付课程订单编号',
    claimed_at DATETIME(3) NOT NULL COMMENT '优惠券领取时间，精确到毫秒',
    reserved_at DATETIME(3) NULL COMMENT '优惠券被订单占用的时间，精确到毫秒',
    used_at DATETIME(3) NULL COMMENT '优惠券核销时间，精确到毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_claim_coupon_user (coupon_id, user_id),
    KEY idx_coupon_claim_reserved_order (reserved_order_id),
    KEY idx_coupon_claim_used_order (used_order_id)
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

CREATE TABLE IF NOT EXISTS assistant_conversation (
    id VARCHAR(64) PRIMARY KEY COMMENT '学习助理会话不透明编号，由服务端生成',
    owner_user_id BIGINT NOT NULL COMMENT '会话所属用户编号，所有读写必须按此字段隔离',
    course_id BIGINT NOT NULL COMMENT '会话绑定课程编号，创建时完成课程范围校验',
    title VARCHAR(120) NOT NULL COMMENT '会话标题，首版可由首条问题截断生成或由用户修改',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE正常、DELETED已删除',
    next_seq BIGINT NOT NULL DEFAULT 1 COMMENT '会话内下一条消息序号，在会话行锁内分配',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '会话乐观版本号，标题、消息或删除变更时递增',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    KEY idx_assistant_conversation_owner_updated (owner_user_id, deleted, updated_at, id),
    KEY idx_assistant_conversation_course (course_id, owner_user_id, deleted)
) COMMENT='学习助理会话表';

CREATE TABLE IF NOT EXISTS assistant_run (
    id VARCHAR(64) PRIMARY KEY COMMENT '学习助理运行不透明编号，由服务端生成',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属学习助理会话编号',
    owner_user_id BIGINT NOT NULL COMMENT '运行所属用户编号，用于所有权隔离和幂等约束',
    client_request_id VARCHAR(64) NOT NULL COMMENT '浏览器生成的请求幂等编号，同一用户内唯一',
    request_hash CHAR(64) NOT NULL COMMENT '会话编号和问题正文的SHA-256摘要，用于识别幂等参数冲突',
    status VARCHAR(20) NOT NULL COMMENT '运行状态：RUNNING执行中、COMPLETED完成、FAILED失败、CANCELLED取消、INTERRUPTED中断',
    lease_owner VARCHAR(64) NULL COMMENT '当前执行实例持有的随机租约编号，终态时清空',
    lease_expires_at DATETIME(3) NULL COMMENT '运行租约固定截止时间，过期后不得写入成功回答',
    message MEDIUMTEXT NOT NULL COMMENT '本次用户问题快照，用于幂等结果与失败重试展示',
    user_message_id VARCHAR(64) NOT NULL COMMENT '本次运行对应的用户消息编号',
    assistant_message_id VARCHAR(64) NULL COMMENT '运行成功后对应的正式助手消息编号',
    usage_json JSON NULL COMMENT '模型最终用量对象，仅保存受信调用层生成的JSON',
    error_code VARCHAR(64) NULL COMMENT '失败、取消或中断的稳定错误编码',
    completed_at DATETIME(3) NULL COMMENT '运行进入终态的时间，精确到毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_run_owner_request (owner_user_id, client_request_id),
    KEY idx_assistant_run_conversation_status (conversation_id, status, lease_expires_at),
    KEY idx_assistant_run_recovery (status, lease_expires_at, id)
) COMMENT='学习助理模型运行及幂等租约表';

CREATE TABLE IF NOT EXISTS assistant_message (
    id VARCHAR(64) PRIMARY KEY COMMENT '学习助理消息不透明编号，由服务端生成',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属学习助理会话编号',
    seq BIGINT NOT NULL COMMENT '会话内严格递增消息序号，在会话行锁内分配',
    role VARCHAR(20) NOT NULL COMMENT '消息角色：USER用户、ASSISTANT学习助理',
    content MEDIUMTEXT NOT NULL COMMENT '正式消息正文；助手草稿不得写入本表',
    status VARCHAR(20) NOT NULL COMMENT '消息状态：COMPLETED已完成；失败运行不创建助手消息',
    run_id VARCHAR(64) NOT NULL COMMENT '产生或接收该消息的学习助理运行编号',
    sources_json JSON NULL COMMENT '助手回答最终校验通过的来源数组；用户消息为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_message_conversation_seq (conversation_id, seq),
    KEY idx_assistant_message_run (run_id),
    KEY idx_assistant_message_conversation_created (conversation_id, created_at, id)
) COMMENT='学习助理正式聊天消息表';

CREATE TABLE IF NOT EXISTS assistant_message_source (
    id VARCHAR(64) PRIMARY KEY COMMENT '消息来源明细不透明编号，由服务端生成',
    message_id VARCHAR(64) NOT NULL COMMENT '所属正式助手消息编号',
    document_id BIGINT NOT NULL COMMENT '来源知识文档编号，关联课程知识文档表',
    chunk_id BIGINT NOT NULL COMMENT '来源知识片段编号，关联知识片段表',
    material_version BIGINT NOT NULL COMMENT '回答生成及最终复核时采用的材料版本号',
    source_kind VARCHAR(32) NOT NULL COMMENT '来源类型白名单：MATERIAL课程材料',
    citation_id VARCHAR(64) NULL COMMENT '回答正文中使用的引用标识',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_message_source_chunk (message_id, chunk_id, material_version),
    KEY idx_assistant_message_source_document (document_id, material_version, message_id)
) COMMENT='学习助理回答来源版本明细表';

CREATE TABLE IF NOT EXISTS assistant_tool_execution (
    id VARCHAR(64) PRIMARY KEY COMMENT '工具执行不透明编号，由服务端生成',
    run_id VARCHAR(64) NOT NULL COMMENT '所属学习助理运行编号',
    execution_order INT NOT NULL COMMENT '本次运行内工具执行顺序，从一开始',
    tool_name VARCHAR(64) NOT NULL COMMENT '服务端白名单工具名称',
    tool_call_id VARCHAR(128) NULL COMMENT '模型工具调用编号，用于单次运行内去重',
    status VARCHAR(20) NOT NULL COMMENT '工具状态：RUNNING执行中、COMPLETED完成、FAILED失败、CANCELLED取消',
    arguments_hash CHAR(64) NOT NULL COMMENT '规范化工具参数SHA-256摘要，不保存未经授权的完整参数',
    result_summary VARCHAR(1000) NULL COMMENT '安全结果摘要，不保存未经授权的整段工具正文',
    material_version BIGINT NULL COMMENT '工具结果依赖的材料版本号，不依赖材料时为空',
    duration_ms BIGINT NULL COMMENT '工具执行耗时，单位为毫秒',
    error_code VARCHAR(64) NULL COMMENT '工具执行失败的稳定错误编码',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_tool_run_order (run_id, execution_order),
    UNIQUE KEY uk_assistant_tool_run_call (run_id, tool_call_id),
    KEY idx_assistant_tool_run_status (run_id, status)
) COMMENT='学习助理只读工具执行状态表';

-- 向量投影独立记录当前模型各来源代次的同步与删除补偿；不修改业务材料。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_vector_projection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '向量投影同步记录主键编号',
    document_id BIGINT NOT NULL COMMENT '知识文档编号，关联知识文档表',
    source_version BIGINT NOT NULL COMMENT '本次投影对应的知识来源版本号',
    model_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '模型及切分策略标识，不同标识隔离投影',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '投影状态：PENDING待同步、INDEXED已同步、FAILED失败待重试、REMOVED已删除',
    cleanup_pending TINYINT NOT NULL DEFAULT 1 COMMENT '旧代次清理补偿标记：1待清理、0已完成；完成状态后崩溃可继续清理',
    attempts INT NOT NULL DEFAULT 0 COMMENT '本代次累计同步失败次数，最多三十次；重建时清零',
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次允许重试时间，精确到毫秒；失败按三十秒至一小时指数退避',
    last_error VARCHAR(120) NULL COMMENT '最近失败的固定错误代码，禁止存储正文或外部异常消息',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_knowledge_vector_generation (document_id, source_version, model_id),
    KEY idx_knowledge_vector_due (model_id, status, next_retry_at, id)
) COMMENT='知识文档向量投影代次同步与删除补偿表';
