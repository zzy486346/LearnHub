-- 点赞关系索引与批次幂等日志。面向 MySQL 8.4，只执行一次。
SET NAMES utf8mb4;
ALTER TABLE like_record
    ADD COLUMN event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '点赞事件单调序列，用于拒绝旧事件覆盖新状态' AFTER active,
    ADD KEY idx_like_target_active (target_type, target_id, active);

CREATE TABLE IF NOT EXISTS like_sync_batch (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    batch_id VARCHAR(64) NOT NULL COMMENT '点赞同步批次唯一编号，用于刷库幂等',
    status VARCHAR(20) NOT NULL COMMENT '同步批次状态：PROCESSING处理中，SUCCESS已完成',
    relation_count INT NOT NULL DEFAULT 0 COMMENT '当前批次同步的用户目标关系数量',
    target_count INT NOT NULL DEFAULT 0 COMMENT '当前批次同步的互动目标数量',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_like_sync_batch (batch_id)
) COMMENT='点赞批量同步幂等记录表';

CREATE TABLE IF NOT EXISTS like_event_inbox (
    id BIGINT PRIMARY KEY COMMENT '主键编号',
    event_id VARCHAR(64) NOT NULL COMMENT '点赞事件全局唯一编号，用于消息幂等',
    user_id BIGINT NOT NULL COMMENT '用户编号，关联用户表',
    target_type VARCHAR(20) NOT NULL COMMENT '互动目标类型：COURSE课程、QUESTION问题、ANSWER回答',
    target_id BIGINT NOT NULL COMMENT '互动目标编号，由目标类型确定关联表',
    liked TINYINT NOT NULL COMMENT '点赞事件期望状态：0取消点赞，1点赞',
    event_sequence BIGINT NOT NULL COMMENT '点赞事件单调序列，用于拒绝旧事件覆盖新状态',
    event_version INT NOT NULL COMMENT '点赞事件协议版本号',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '点赞事件处理状态：PENDING待同步，PROCESSED已同步',
    occurred_at DATETIME(3) NOT NULL COMMENT '点赞事件发生时间，精确到毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    processed_at DATETIME(3) NULL COMMENT '点赞事件完成数据库同步的时间，未处理时为空',
    UNIQUE KEY uk_like_event_inbox_event (event_id),
    KEY idx_like_event_inbox_status (status, id)
) COMMENT='点赞事件持久化收件箱表';

-- 回滚说明（确认没有待处理批次后人工执行）：
-- DROP TABLE like_sync_batch;
-- DROP TABLE like_event_inbox;
-- ALTER TABLE like_record DROP COLUMN event_sequence;
-- ALTER TABLE like_record DROP INDEX idx_like_target_active;
