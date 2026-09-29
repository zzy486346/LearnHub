USE learnhub;

CREATE TABLE IF NOT EXISTS media_asset (
    id BIGINT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    bucket_name VARCHAR(128) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    original_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    file_size BIGINT NOT NULL,
    etag VARCHAR(128),
    status VARCHAR(20) NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    biz_id BIGINT,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted TINYINT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_media_object_key (object_key),
    KEY idx_media_owner_created (owner_id, created_at),
    KEY idx_media_biz (biz_type, biz_id)
);
