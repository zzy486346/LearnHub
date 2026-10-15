-- 创建课程课时知识文档和版本片段表；仅创建结构，不自动导入任何正文。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识文档主键编号',
    source_key VARCHAR(191) NOT NULL COMMENT '外部知识来源稳定标识，同一来源全局唯一',
    title VARCHAR(160) NOT NULL COMMENT '知识文档标题',
    course_id BIGINT NOT NULL COMMENT '绑定课程编号，关联课程信息表',
    lesson_id BIGINT NOT NULL COMMENT '绑定课时编号，关联课程课时表',
    visibility VARCHAR(32) NOT NULL DEFAULT 'COURSE_LESSON' COMMENT '正文可见范围：COURSE_LESSON按绑定课程和课时鉴权',
    content_hash CHAR(64) NOT NULL COMMENT '标题、归属和场景正文的SHA-256摘要，用于幂等导入',
    source_version BIGINT NOT NULL DEFAULT 1 COMMENT '知识来源版本号，内容变化时由服务端递增',
    indexed_version BIGINT NULL COMMENT '已完整写入并刷新知识索引的来源版本号',
    index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '索引状态：PENDING待同步、INDEXED已同步、FAILED同步失败',
    attempts INT NOT NULL DEFAULT 0 COMMENT '当前版本索引同步失败次数',
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '当前版本下次允许重试索引的时间，精确到毫秒',
    last_error VARCHAR(1000) NULL COMMENT '当前版本最近一次索引同步失败摘要',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    UNIQUE KEY uk_knowledge_document_source (source_key),
    KEY idx_knowledge_document_due (index_status, next_retry_at, id),
    KEY idx_knowledge_document_lesson (course_id, lesson_id)
) COMMENT='课程课时知识文档表';

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '知识片段主键编号',
    document_id BIGINT NOT NULL COMMENT '所属知识文档编号，关联知识文档表',
    source_version BIGINT NOT NULL COMMENT '片段所属知识来源版本号',
    chunk_order INT NOT NULL COMMENT '片段在当前文档版本内的顺序，从零开始',
    heading VARCHAR(255) NULL COMMENT '片段所属场景标题',
    content TEXT NOT NULL COMMENT '知识片段正文，最多六百个Unicode代码点',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_knowledge_chunk_order (document_id, source_version, chunk_order),
    KEY idx_knowledge_chunk_version (document_id, source_version, id)
) COMMENT='知识文档版本片段表';
