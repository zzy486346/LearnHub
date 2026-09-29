SET NAMES utf8mb4;
USE learnhub;

SET @user_avatar_media_id_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' AND COLUMN_NAME = 'avatar_media_id'
);
SET @user_avatar_media_id_sql = IF(
    @user_avatar_media_id_exists = 0,
    'ALTER TABLE user ADD COLUMN avatar_media_id BIGINT NULL AFTER avatar_url, ADD KEY idx_user_avatar_media (avatar_media_id)',
    'SELECT 1'
);
PREPARE user_avatar_media_stmt FROM @user_avatar_media_id_sql;
EXECUTE user_avatar_media_stmt;
DEALLOCATE PREPARE user_avatar_media_stmt;

SET @lesson_media_asset_id_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'course_lesson' AND COLUMN_NAME = 'media_asset_id'
);
SET @lesson_media_asset_id_sql = IF(
    @lesson_media_asset_id_exists = 0,
    'ALTER TABLE course_lesson ADD COLUMN media_asset_id BIGINT NULL AFTER media_url, ADD KEY idx_lesson_media_asset (media_asset_id)',
    'SELECT 1'
);
PREPARE lesson_media_asset_stmt FROM @lesson_media_asset_id_sql;
EXECUTE lesson_media_asset_stmt;
DEALLOCATE PREPARE lesson_media_asset_stmt;
