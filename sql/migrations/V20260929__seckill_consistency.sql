-- 从旧版 schema 升级秒杀订单状态机。此脚本面向 MySQL 8.4，只执行一次。
SET NAMES utf8mb4;
ALTER TABLE seckill_order
    ADD COLUMN publish_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '消息发布状态：PENDING待发布、PUBLISHING发布中、SENT已发送、FAILED发布失败' AFTER status,
    ADD COLUMN publish_attempts INT NOT NULL DEFAULT 0 COMMENT '消息累计发布尝试次数' AFTER publish_status,
    ADD COLUMN compensated TINYINT NOT NULL DEFAULT 0 COMMENT '库存补偿完成标记：0未完成，1已完成' AFTER publish_attempts,
    ADD COLUMN reserved_at DATETIME(3) NULL COMMENT 'Redis库存预留及请求受理时间，精确到毫秒' AFTER failure_reason,
    ADD COLUMN next_retry_at DATETIME(3) NULL COMMENT '发布重试或消费确认超时后的下次处理时间' AFTER reserved_at;

UPDATE seckill_order
SET reserved_at = COALESCE(reserved_at, created_at),
    next_retry_at = CASE WHEN status = 'PENDING' THEN COALESCE(next_retry_at, created_at) ELSE next_retry_at END
WHERE reserved_at IS NULL
   OR (status = 'PENDING' AND next_retry_at IS NULL);

ALTER TABLE seckill_order
    MODIFY COLUMN reserved_at DATETIME(3) NOT NULL COMMENT 'Redis库存预留及请求受理时间，精确到毫秒',
    ADD KEY idx_seckill_recovery (status, compensated, next_retry_at);

-- 回滚说明（仅在确认没有新版本订单后人工执行）：
-- ALTER TABLE seckill_order DROP INDEX idx_seckill_recovery,
--   DROP COLUMN next_retry_at, DROP COLUMN reserved_at, DROP COLUMN compensated,
--   DROP COLUMN publish_attempts, DROP COLUMN publish_status;
