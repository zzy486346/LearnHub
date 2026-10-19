CREATE TABLE IF NOT EXISTS assistant_learning_preference (
    id CHAR(36) PRIMARY KEY COMMENT '服务端生成的学习偏好UUID编号',
    owner_user_id BIGINT NOT NULL COMMENT '偏好所属用户编号，所有操作按本人隔离',
    version BIGINT NOT NULL DEFAULT 1 COMMENT '当前偏好意图版本，从1开始，修改或忘记递增',
    category VARCHAR(12) NOT NULL COMMENT '学习偏好分类：STYLE风格、PACE节奏、LEVEL程度、GOAL目标',
    text VARCHAR(200) NOT NULL COMMENT '经过敏感信息策略校验的偏好正文，忘记时清空',
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING' COMMENT '同步状态：PENDING待写入、ACTIVE可使用、DELETING待远端删除、DELETED已删除',
    source_conversation_id CHAR(36) NULL COMMENT '可选来源会话编号，使用前重新校验本人及未删除',
    source_course_id BIGINT NULL COMMENT '来源会话绑定课程编号，由服务端读取并复核权限',
    attempts INT NOT NULL DEFAULT 0 COMMENT '当前版本连续同步失败次数，最大1000000',
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '允许下次补偿时间，失败指数退避30秒至1小时',
    last_error VARCHAR(64) NULL COMMENT '安全错误代码，不保存供应商异常或偏好原文',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1忘记墓碑',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间，精确到毫秒',
    KEY idx_preference_owner (owner_user_id, deleted, updated_at),
    KEY idx_preference_retry (status, next_retry_at, id),
    KEY idx_preference_source (source_conversation_id, owner_user_id)
) COMMENT='学习助理长期偏好事实与远端同步意图表';
