SET NAMES utf8mb4;
USE learnhub;

CREATE TABLE IF NOT EXISTS search_index_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键编号',
    course_id BIGINT NOT NULL COMMENT '待同步的课程编号，关联课程表',
    generation BIGINT NOT NULL DEFAULT 1 COMMENT '任务代次，每次课程变更时递增，用于完成和失败更新的并发校验',
    attempts INT NOT NULL DEFAULT 0 COMMENT '当前代次已失败的同步尝试次数',
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次允许执行索引同步的时间，精确到毫秒',
    last_error VARCHAR(1000) NULL COMMENT '最近一次索引同步失败的错误摘要，成功或新代次时为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_search_index_task_course (course_id),
    KEY idx_search_index_task_due (next_retry_at, id)
) COMMENT='课程搜索索引同步任务表';
