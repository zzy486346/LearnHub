-- 学习助理只读工具完整结果的有界短期存储；可重复执行，不修改既有正式历史。
SET NAMES utf8mb4;

SET @learnhub_add_tool_result_bytes = IF(
    EXISTS (SELECT 1 FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'assistant_run'
              AND COLUMN_NAME = 'tool_result_bytes'),
    'SELECT 1',
    'ALTER TABLE assistant_run ADD COLUMN tool_result_bytes BIGINT NOT NULL DEFAULT 0 COMMENT ''本次运行已预留的工具结果累计UTF8字节数，过期或清理后不退还以防绕过上限'' AFTER usage_json'
);
PREPARE learnhub_tool_result_bytes_stmt FROM @learnhub_add_tool_result_bytes;
EXECUTE learnhub_tool_result_bytes_stmt;
DEALLOCATE PREPARE learnhub_tool_result_bytes_stmt;

CREATE TABLE IF NOT EXISTS assistant_tool_result (
    result_ref VARCHAR(64) PRIMARY KEY COMMENT '服务端生成的工具结果不透明引用，模型只能按此引用受控回读',
    execution_id VARCHAR(64) NOT NULL COMMENT '产生该结果的工具执行编号，每次执行最多保存一份完整结果',
    owner_user_id BIGINT NOT NULL COMMENT '结果所属用户编号，写入和回读均按本人隔离',
    course_id BIGINT NOT NULL COMMENT '结果所属课程编号，回读时必须与当前运行课程一致',
    conversation_id VARCHAR(64) NOT NULL COMMENT '结果所属学习助理会话编号',
    run_id VARCHAR(64) NOT NULL COMMENT '结果所属学习助理运行编号，单运行累计字节受限',
    tool_name VARCHAR(64) NOT NULL COMMENT '产生结果的服务端只读工具白名单名称',
    result_json MEDIUMTEXT NOT NULL COMMENT '工具完整结果严格JSON对象，仅在授权回读后交给调用方',
    projection_json MEDIUMTEXT NOT NULL COMMENT '首次模型调用使用的有界确定性投影严格JSON对象',
    dependencies_json MEDIUMTEXT NOT NULL COMMENT '结果权限和版本依赖信封JSON，每次回读后由应用层重新复核',
    result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整结果原始UTF8字节的SHA-256摘要',
    result_bytes BIGINT NOT NULL COMMENT '完整结果、投影和依赖信封的UTF8累计字节数，用于单结果及单运行限额',
    expires_at DATETIME(3) NOT NULL COMMENT '结果过期时间，过期后禁止回读并由清理任务物理删除',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：0正常，1所属会话已删除待清理',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，记录修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_tool_result_execution (execution_id),
    KEY idx_assistant_tool_result_run_bytes (run_id, deleted, expires_at),
    KEY idx_assistant_tool_result_conversation (conversation_id, owner_user_id, deleted),
    KEY idx_assistant_tool_result_expiry (deleted, expires_at, result_ref)
) COMMENT='学习助理只读工具完整结果短期存储表';
