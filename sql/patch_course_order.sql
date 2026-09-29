USE learnhub;

CREATE TABLE IF NOT EXISTS course_order (
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    course_id BIGINT NOT NULL,
    course_title VARCHAR(160) NOT NULL,
    original_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    paid_amount DECIMAL(12,2) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    paid_at DATETIME(3) NULL,
    UNIQUE KEY uk_course_order_no (order_no),
    UNIQUE KEY uk_course_order_user_course (user_id, course_id),
    KEY idx_course_order_course_status (course_id, status)
);

SET @course_order_updated_at_exists = (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'course_order'
      AND COLUMN_NAME = 'updated_at'
);
SET @course_order_updated_at_sql = IF(
    @course_order_updated_at_exists = 0,
    'ALTER TABLE course_order ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) AFTER created_at',
    'SELECT 1'
);
PREPARE course_order_updated_at_stmt FROM @course_order_updated_at_sql;
EXECUTE course_order_updated_at_stmt;
DEALLOCATE PREPARE course_order_updated_at_stmt;
