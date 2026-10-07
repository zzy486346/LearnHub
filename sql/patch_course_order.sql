SET NAMES utf8mb4;
USE learnhub;

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

SET @course_order_updated_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'course_order'
      AND COLUMN_NAME = 'updated_at'
);
SET @course_order_updated_at_sql = IF(
    @course_order_updated_at_exists = 0,
    'ALTER TABLE course_order ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间，记录修改时自动更新，精确到毫秒'' AFTER created_at',
    'SELECT 1'
);
PREPARE course_order_updated_at_stmt FROM @course_order_updated_at_sql;
EXECUTE course_order_updated_at_stmt;
DEALLOCATE PREPARE course_order_updated_at_stmt;
