-- 为课程课时增加逻辑删除标记，保留媒体引用和历史学习记录。
-- 可重复执行；既有课时统一保持正常状态（deleted = 0）。
SET NAMES utf8mb4;
USE learnhub;

SET @learnhub_add_course_lesson_deleted = IF(
    EXISTS (
        SELECT 1
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = 'course_lesson'
          AND COLUMN_NAME = 'deleted'
    ),
    'SELECT 1',
    'ALTER TABLE course_lesson ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT ''逻辑删除标记：0正常，1已删除'' AFTER updated_at'
);
PREPARE learnhub_course_lesson_soft_delete_stmt FROM @learnhub_add_course_lesson_deleted;
EXECUTE learnhub_course_lesson_soft_delete_stmt;
DEALLOCATE PREPARE learnhub_course_lesson_soft_delete_stmt;

-- 验收：应返回 deleted、tinyint、NO、0 及中文注释。
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'course_lesson'
  AND COLUMN_NAME = 'deleted';
