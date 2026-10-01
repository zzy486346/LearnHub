-- 点赞关系索引与批次幂等日志。面向 MySQL 8.4，只执行一次。
ALTER TABLE like_record
    ADD COLUMN event_sequence BIGINT NOT NULL DEFAULT 0 AFTER active,
    ADD KEY idx_like_target_active (target_type, target_id, active);

CREATE TABLE IF NOT EXISTS like_sync_batch (
    id BIGINT PRIMARY KEY,
    batch_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    relation_count INT NOT NULL DEFAULT 0,
    target_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_like_sync_batch (batch_id)
);

CREATE TABLE IF NOT EXISTS like_event_inbox (
    id BIGINT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id BIGINT NOT NULL,
    liked TINYINT NOT NULL,
    event_sequence BIGINT NOT NULL,
    event_version INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    occurred_at DATETIME(3) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    processed_at DATETIME(3) NULL,
    UNIQUE KEY uk_like_event_inbox_event (event_id),
    KEY idx_like_event_inbox_status (status, id)
);

-- 回滚说明（确认没有待处理批次后人工执行）：
-- DROP TABLE like_sync_batch;
-- DROP TABLE like_event_inbox;
-- ALTER TABLE like_record DROP COLUMN event_sequence;
-- ALTER TABLE like_record DROP INDEX idx_like_target_active;
