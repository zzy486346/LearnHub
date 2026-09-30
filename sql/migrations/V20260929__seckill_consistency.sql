-- 从旧版 schema 升级秒杀订单状态机。此脚本面向 MySQL 8.4，只执行一次。
ALTER TABLE seckill_order
    ADD COLUMN publish_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER status,
    ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0 AFTER publish_status,
    ADD COLUMN compensated TINYINT NOT NULL DEFAULT 0 AFTER publish_attempts,
    ADD COLUMN reserved_at DATETIME(3) NULL AFTER failure_reason,
    ADD COLUMN next_retry_at DATETIME(3) NULL AFTER reserved_at;

UPDATE seckill_order
SET reserved_at = COALESCE(reserved_at, created_at),
    next_retry_at = CASE WHEN status = 'PENDING' THEN COALESCE(next_retry_at, created_at) ELSE next_retry_at END
WHERE reserved_at IS NULL
   OR (status = 'PENDING' AND next_retry_at IS NULL);

ALTER TABLE seckill_order
    MODIFY COLUMN reserved_at DATETIME(3) NOT NULL,
    ADD KEY idx_seckill_recovery (status, compensated, next_retry_at);

-- 回滚说明（仅在确认没有新版本订单后人工执行）：
-- ALTER TABLE seckill_order DROP INDEX idx_seckill_recovery,
--   DROP COLUMN next_retry_at, DROP COLUMN reserved_at, DROP COLUMN compensated,
--   DROP COLUMN publish_attempts, DROP COLUMN publish_status;
