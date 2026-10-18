-- 学习助理正式会话、消息、运行和工具执行持久化结构。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS assistant_conversation (
    id VARCHAR(64) PRIMARY KEY COMMENT '学习助理会话不透明编号，由服务端生成',
    owner_user_id BIGINT NOT NULL COMMENT '会话所属用户编号，所有读写必须按此字段隔离',
    course_id BIGINT NOT NULL COMMENT '会话绑定课程编号，创建时完成课程范围校验',
    title VARCHAR(120) NOT NULL COMMENT '会话标题，首版可由首条问题截断生成或由用户修改',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE正常、DELETED已删除',
    next_seq BIGINT NOT NULL DEFAULT 1 COMMENT '会话内下一条消息序号，在会话行锁内分配',
    version BIGINT NOT NULL DEFAULT 0 COMMENT '会话乐观版本号，标题、消息或删除变更时递增',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1已删除',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    KEY idx_assistant_conversation_owner_updated (owner_user_id, deleted, updated_at, id),
    KEY idx_assistant_conversation_course (course_id, owner_user_id, deleted)
) COMMENT='学习助理会话表';

CREATE TABLE IF NOT EXISTS assistant_run (
    id VARCHAR(64) PRIMARY KEY COMMENT '学习助理运行不透明编号，由服务端生成',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属学习助理会话编号',
    owner_user_id BIGINT NOT NULL COMMENT '运行所属用户编号，用于所有权隔离和幂等约束',
    client_request_id VARCHAR(64) NOT NULL COMMENT '浏览器生成的请求幂等编号，同一用户内唯一',
    request_hash CHAR(64) NOT NULL COMMENT '会话编号和问题正文的SHA-256摘要，用于识别幂等参数冲突',
    status VARCHAR(20) NOT NULL COMMENT '运行状态：RUNNING执行中、COMPLETED完成、FAILED失败、CANCELLED取消、INTERRUPTED中断',
    lease_owner VARCHAR(64) NULL COMMENT '当前执行实例持有的随机租约编号，终态时清空',
    lease_expires_at DATETIME(3) NULL COMMENT '运行租约固定截止时间，过期后不得写入成功回答',
    message MEDIUMTEXT NOT NULL COMMENT '本次用户问题快照，用于幂等结果与失败重试展示',
    user_message_id VARCHAR(64) NOT NULL COMMENT '本次运行对应的用户消息编号',
    assistant_message_id VARCHAR(64) NULL COMMENT '运行成功后对应的正式助手消息编号',
    usage_json JSON NULL COMMENT '模型最终用量对象，仅保存受信调用层生成的JSON',
    error_code VARCHAR(64) NULL COMMENT '失败、取消或中断的稳定错误编码',
    completed_at DATETIME(3) NULL COMMENT '运行进入终态的时间，精确到毫秒',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_run_owner_request (owner_user_id, client_request_id),
    KEY idx_assistant_run_conversation_status (conversation_id, status, lease_expires_at),
    KEY idx_assistant_run_recovery (status, lease_expires_at, id)
) COMMENT='学习助理模型运行及幂等租约表';

CREATE TABLE IF NOT EXISTS assistant_message (
    id VARCHAR(64) PRIMARY KEY COMMENT '学习助理消息不透明编号，由服务端生成',
    conversation_id VARCHAR(64) NOT NULL COMMENT '所属学习助理会话编号',
    seq BIGINT NOT NULL COMMENT '会话内严格递增消息序号，在会话行锁内分配',
    role VARCHAR(20) NOT NULL COMMENT '消息角色：USER用户、ASSISTANT学习助理',
    content MEDIUMTEXT NOT NULL COMMENT '正式消息正文；助手草稿不得写入本表',
    status VARCHAR(20) NOT NULL COMMENT '消息状态：COMPLETED已完成；失败运行不创建助手消息',
    run_id VARCHAR(64) NOT NULL COMMENT '产生或接收该消息的学习助理运行编号',
    sources_json JSON NULL COMMENT '助手回答最终校验通过的来源数组；用户消息为空',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_message_conversation_seq (conversation_id, seq),
    KEY idx_assistant_message_run (run_id),
    KEY idx_assistant_message_conversation_created (conversation_id, created_at, id)
) COMMENT='学习助理正式聊天消息表';

CREATE TABLE IF NOT EXISTS assistant_message_source (
    id VARCHAR(64) PRIMARY KEY COMMENT '消息来源明细不透明编号，由服务端生成',
    message_id VARCHAR(64) NOT NULL COMMENT '所属正式助手消息编号',
    document_id BIGINT NOT NULL COMMENT '来源知识文档编号，关联课程知识文档表',
    chunk_id BIGINT NOT NULL COMMENT '来源知识片段编号，关联知识片段表',
    material_version BIGINT NOT NULL COMMENT '回答生成及最终复核时采用的材料版本号',
    source_kind VARCHAR(32) NOT NULL COMMENT '来源类型白名单：MATERIAL课程材料',
    citation_id VARCHAR(64) NULL COMMENT '回答正文中使用的引用标识',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_message_source_chunk (message_id, chunk_id, material_version),
    KEY idx_assistant_message_source_document (document_id, material_version, message_id)
) COMMENT='学习助理回答来源版本明细表';

CREATE TABLE IF NOT EXISTS assistant_tool_execution (
    id VARCHAR(64) PRIMARY KEY COMMENT '工具执行不透明编号，由服务端生成',
    run_id VARCHAR(64) NOT NULL COMMENT '所属学习助理运行编号',
    execution_order INT NOT NULL COMMENT '本次运行内工具执行顺序，从一开始',
    tool_name VARCHAR(64) NOT NULL COMMENT '服务端白名单工具名称',
    tool_call_id VARCHAR(128) NULL COMMENT '模型工具调用编号，用于单次运行内去重',
    status VARCHAR(20) NOT NULL COMMENT '工具状态：RUNNING执行中、COMPLETED完成、FAILED失败、CANCELLED取消',
    arguments_hash CHAR(64) NOT NULL COMMENT '规范化工具参数SHA-256摘要，不保存未经授权的完整参数',
    result_summary VARCHAR(1000) NULL COMMENT '安全结果摘要，不保存未经授权的整段工具正文',
    material_version BIGINT NULL COMMENT '工具结果依赖的材料版本号，不依赖材料时为空',
    duration_ms BIGINT NULL COMMENT '工具执行耗时，单位为毫秒',
    error_code VARCHAR(64) NULL COMMENT '工具执行失败的稳定错误编码',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_tool_run_order (run_id, execution_order),
    UNIQUE KEY uk_assistant_tool_run_call (run_id, tool_call_id),
    KEY idx_assistant_tool_run_status (run_id, status)
) COMMENT='学习助理只读工具执行状态表';
