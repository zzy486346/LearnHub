-- 补齐课程订单金额、优惠券占用和本机演示支付状态，支持重复执行。
SET NAMES utf8mb4;
USE learnhub;

SET @learnhub_add_order_coupon_claim = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order' AND COLUMN_NAME = 'coupon_claim_id'),
    'SELECT 1',
    'ALTER TABLE course_order ADD COLUMN coupon_claim_id BIGINT NULL COMMENT ''订单使用的用户优惠券领取记录编号'' AFTER original_amount'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_order_coupon_claim;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_order_discount = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order' AND COLUMN_NAME = 'discount_amount'),
    'SELECT 1',
    'ALTER TABLE course_order ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT ''订单优惠抵扣金额，单位为人民币元'' AFTER coupon_claim_id'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_order_discount;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_order_payable = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order' AND COLUMN_NAME = 'payable_amount'),
    'SELECT 1',
    'ALTER TABLE course_order ADD COLUMN payable_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT ''订单应付金额，单位为人民币元'' AFTER discount_amount'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_order_payable;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_order_payment_mode = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order' AND COLUMN_NAME = 'payment_mode'),
    'SELECT 1',
    'ALTER TABLE course_order ADD COLUMN payment_mode VARCHAR(24) NOT NULL DEFAULT ''DISABLED'' COMMENT ''订单支付模式：DISABLED未开放、LOCAL_DEMO本机演示、LEGACY_DEMO历史演示'' AFTER paid_amount'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_order_payment_mode;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_order_expires = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order' AND COLUMN_NAME = 'expires_at'),
    'SELECT 1',
    'ALTER TABLE course_order ADD COLUMN expires_at DATETIME(3) NULL COMMENT ''待支付订单失效时间，精确到毫秒'' AFTER paid_at'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_order_expires;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_order_cancelled = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order' AND COLUMN_NAME = 'cancelled_at'),
    'SELECT 1',
    'ALTER TABLE course_order ADD COLUMN cancelled_at DATETIME(3) NULL COMMENT ''订单主动取消或超时关闭时间，精确到毫秒'' AFTER expires_at'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_order_cancelled;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_order_refunded = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order' AND COLUMN_NAME = 'refunded_at'),
    'SELECT 1',
    'ALTER TABLE course_order ADD COLUMN refunded_at DATETIME(3) NULL COMMENT ''演示订单退款完成时间，精确到毫秒'' AFTER cancelled_at'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_order_refunded;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_claim_reserved_order = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupon_claim' AND COLUMN_NAME = 'reserved_order_id'),
    'SELECT 1',
    'ALTER TABLE coupon_claim ADD COLUMN reserved_order_id BIGINT NULL COMMENT ''当前占用该券的待支付课程订单编号'' AFTER status'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_claim_reserved_order;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_claim_used_order = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupon_claim' AND COLUMN_NAME = 'used_order_id'),
    'SELECT 1',
    'ALTER TABLE coupon_claim ADD COLUMN used_order_id BIGINT NULL COMMENT ''核销该券的已支付课程订单编号'' AFTER reserved_order_id'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_claim_used_order;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_claim_reserved_at = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupon_claim' AND COLUMN_NAME = 'reserved_at'),
    'SELECT 1',
    'ALTER TABLE coupon_claim ADD COLUMN reserved_at DATETIME(3) NULL COMMENT ''优惠券被订单占用的时间，精确到毫秒'' AFTER claimed_at'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_claim_reserved_at;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_claim_used_at = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupon_claim' AND COLUMN_NAME = 'used_at'),
    'SELECT 1',
    'ALTER TABLE coupon_claim ADD COLUMN used_at DATETIME(3) NULL COMMENT ''优惠券核销时间，精确到毫秒'' AFTER reserved_at'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_claim_used_at;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

UPDATE course_order
SET payable_amount = original_amount,
    expires_at = DATE_ADD(created_at, INTERVAL 30 MINUTE),
    updated_at = updated_at
WHERE status = 'PENDING'
  AND payment_mode = 'DISABLED'
  AND expires_at IS NULL
  AND coupon_claim_id IS NULL
  AND discount_amount = 0.00
  AND payable_amount = 0.00;

UPDATE course_order
SET payable_amount = original_amount,
    payment_mode = 'LEGACY_DEMO',
    updated_at = updated_at
WHERE status = 'PAID'
  AND payment_mode = 'DISABLED'
  AND coupon_claim_id IS NULL
  AND discount_amount = 0.00
  AND payable_amount = 0.00;

DROP PROCEDURE IF EXISTS learnhub_commerce_comment_column;
DELIMITER $$
CREATE PROCEDURE learnhub_commerce_comment_column(
    IN table_name_value VARCHAR(64),
    IN column_name_value VARCHAR(64),
    IN comment_value TEXT
)
BEGIN
    SELECT CONCAT('ALTER TABLE `', c.TABLE_NAME, '` MODIFY COLUMN `', c.COLUMN_NAME, '` ', c.COLUMN_TYPE,
                  IF(c.CHARACTER_SET_NAME IS NULL, '', CONCAT(' CHARACTER SET ', c.CHARACTER_SET_NAME,
                                                              ' COLLATE ', c.COLLATION_NAME)),
                  IF(c.IS_NULLABLE = 'NO', ' NOT NULL', ' NULL'),
                  CASE WHEN c.COLUMN_DEFAULT IS NULL THEN IF(c.IS_NULLABLE = 'YES', ' DEFAULT NULL', '')
                       WHEN c.EXTRA LIKE '%DEFAULT_GENERATED%' THEN CONCAT(' DEFAULT ', c.COLUMN_DEFAULT)
                       ELSE CONCAT(' DEFAULT ', QUOTE(c.COLUMN_DEFAULT)) END,
                  IF(TRIM(REPLACE(c.EXTRA, 'DEFAULT_GENERATED', '')) = '', '',
                     CONCAT(' ', TRIM(REPLACE(c.EXTRA, 'DEFAULT_GENERATED', '')))),
                  ' COMMENT ', QUOTE(comment_value))
      INTO @learnhub_commerce_comment_sql
      FROM information_schema.COLUMNS c
     WHERE c.TABLE_SCHEMA = DATABASE()
       AND c.TABLE_NAME = table_name_value
       AND c.COLUMN_NAME = column_name_value;
    IF @learnhub_commerce_comment_sql IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '需要更新注释的字段不存在';
    END IF;
    PREPARE learnhub_commerce_comment_stmt FROM @learnhub_commerce_comment_sql;
    EXECUTE learnhub_commerce_comment_stmt;
    DEALLOCATE PREPARE learnhub_commerce_comment_stmt;
    SET @learnhub_commerce_comment_sql = NULL;
END$$
DELIMITER ;

CALL learnhub_commerce_comment_column(
    'course_order', 'status',
    '课程订单状态：PENDING待支付、PAID已支付、CANCELLED已取消、CLOSED超时关闭、REFUNDED已退款'
);
CALL learnhub_commerce_comment_column(
    'coupon_claim', 'status',
    '领取券状态：AVAILABLE可用、RESERVED订单占用、USED已核销、EXPIRED已过期；到期状态可按有效期计算'
);
DROP PROCEDURE learnhub_commerce_comment_column;

SET @learnhub_add_order_coupon_index = IF(
    EXISTS (SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_order' AND INDEX_NAME = 'idx_course_order_coupon_claim'),
    'SELECT 1',
    'ALTER TABLE course_order ADD KEY idx_course_order_coupon_claim (coupon_claim_id)'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_order_coupon_index;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_claim_reserved_index = IF(
    EXISTS (SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupon_claim' AND INDEX_NAME = 'idx_coupon_claim_reserved_order'),
    'SELECT 1',
    'ALTER TABLE coupon_claim ADD KEY idx_coupon_claim_reserved_order (reserved_order_id)'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_claim_reserved_index;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SET @learnhub_add_claim_used_index = IF(
    EXISTS (SELECT 1 FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupon_claim' AND INDEX_NAME = 'idx_coupon_claim_used_order'),
    'SELECT 1',
    'ALTER TABLE coupon_claim ADD KEY idx_coupon_claim_used_order (used_order_id)'
);
PREPARE learnhub_commerce_stmt FROM @learnhub_add_claim_used_index;
EXECUTE learnhub_commerce_stmt;
DEALLOCATE PREPARE learnhub_commerce_stmt;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME IN ('course_order', 'coupon_claim')
  AND COLUMN_NAME IN ('coupon_claim_id', 'discount_amount', 'payable_amount', 'payment_mode', 'expires_at',
                      'cancelled_at', 'refunded_at', 'reserved_order_id', 'used_order_id', 'reserved_at', 'used_at')
ORDER BY TABLE_NAME, ORDINAL_POSITION;
