-- 向量投影独立记录当前模型各来源代次的同步与删除补偿；不修改业务材料。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_vector_projection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '向量投影同步记录主键编号',
    document_id BIGINT NOT NULL COMMENT '知识文档编号，关联知识文档表',
    source_version BIGINT NOT NULL COMMENT '本次投影对应的知识来源版本号',
    model_id VARCHAR(120) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '模型及切分策略标识，不同标识隔离投影',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '投影状态：PENDING待同步、INDEXED已同步、FAILED失败待重试、REMOVED已删除',
    cleanup_pending TINYINT NOT NULL DEFAULT 1 COMMENT '旧代次清理补偿标记：1待清理、0已完成；完成状态后崩溃可继续清理',
    attempts INT NOT NULL DEFAULT 0 COMMENT '本代次累计同步失败次数，最多三十次；重建时清零',
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次允许重试时间，精确到毫秒；失败按三十秒至一小时指数退避',
    last_error VARCHAR(120) NULL COMMENT '最近失败的固定错误代码，禁止存储正文或外部异常消息',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_knowledge_vector_generation (document_id, source_version, model_id),
    KEY idx_knowledge_vector_due (model_id, status, next_retry_at, id)
) COMMENT='知识文档向量投影代次同步与删除补偿表';
