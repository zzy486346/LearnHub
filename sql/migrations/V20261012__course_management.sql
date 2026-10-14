-- 补齐课程封面媒体关联与课程标签更新时间字段，支持课程管理闭环。
-- 迁移可重复执行，保留既有课程、分类、标签及绑定数据。
SET NAMES utf8mb4;
USE learnhub;

SET @learnhub_add_course_cover_media = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course' AND COLUMN_NAME = 'cover_media_id'
    ),
    'SELECT 1',
    'ALTER TABLE course ADD COLUMN cover_media_id BIGINT NULL COMMENT ''课程封面媒体资源编号，关联媒体资源表'' AFTER cover_url'
);
PREPARE learnhub_course_cover_media_stmt FROM @learnhub_add_course_cover_media;
EXECUTE learnhub_course_cover_media_stmt;
DEALLOCATE PREPARE learnhub_course_cover_media_stmt;

SET @learnhub_add_course_cover_media_index = IF(
    EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course' AND INDEX_NAME = 'idx_course_cover_media'
    ),
    'SELECT 1',
    'ALTER TABLE course ADD KEY idx_course_cover_media (cover_media_id)'
);
PREPARE learnhub_course_cover_media_index_stmt FROM @learnhub_add_course_cover_media_index;
EXECUTE learnhub_course_cover_media_index_stmt;
DEALLOCATE PREPARE learnhub_course_cover_media_index_stmt;

SET @learnhub_add_course_category_name_index = IF(
    EXISTS (
        SELECT 1 FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_category'
          AND INDEX_NAME = 'uk_course_category_name'
    ),
    'SELECT 1',
    'ALTER TABLE course_category ADD UNIQUE KEY uk_course_category_name (name)'
);
PREPARE learnhub_course_category_name_index_stmt FROM @learnhub_add_course_category_name_index;
EXECUTE learnhub_course_category_name_index_stmt;
DEALLOCATE PREPARE learnhub_course_category_name_index_stmt;

SET @learnhub_add_tag_updated_at = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tag' AND COLUMN_NAME = 'updated_at'
    ),
    'SELECT 1',
    'ALTER TABLE tag ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间，记录修改时自动更新，精确到毫秒'' AFTER created_at'
);
PREPARE learnhub_tag_updated_at_stmt FROM @learnhub_add_tag_updated_at;
EXECUTE learnhub_tag_updated_at_stmt;
DEALLOCATE PREPARE learnhub_tag_updated_at_stmt;

SET @learnhub_add_course_tag_created_at = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_tag' AND COLUMN_NAME = 'created_at'
    ),
    'SELECT 1',
    'ALTER TABLE course_tag ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT ''创建时间，精确到毫秒'' AFTER tag_id'
);
PREPARE learnhub_course_tag_created_at_stmt FROM @learnhub_add_course_tag_created_at;
EXECUTE learnhub_course_tag_created_at_stmt;
DEALLOCATE PREPARE learnhub_course_tag_created_at_stmt;

SET @learnhub_add_course_tag_updated_at = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_tag' AND COLUMN_NAME = 'updated_at'
    ),
    'SELECT 1',
    'ALTER TABLE course_tag ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT ''更新时间，记录修改时自动更新，精确到毫秒'' AFTER created_at'
);
PREPARE learnhub_course_tag_updated_at_stmt FROM @learnhub_add_course_tag_updated_at;
EXECUTE learnhub_course_tag_updated_at_stmt;
DEALLOCATE PREPARE learnhub_course_tag_updated_at_stmt;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'course' AND COLUMN_NAME = 'cover_media_id')
    OR (TABLE_NAME = 'tag' AND COLUMN_NAME = 'updated_at')
    OR (TABLE_NAME = 'course_tag' AND COLUMN_NAME IN ('created_at', 'updated_at')))
ORDER BY TABLE_NAME, ORDINAL_POSITION;
