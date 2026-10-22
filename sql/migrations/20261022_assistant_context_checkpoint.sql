-- 上下文检查点仅在启用新上下文路径时使用；不要自动对业务库执行。
SET NAMES utf8mb4;
CREATE TABLE IF NOT EXISTS assistant_context_checkpoint (
    id VARCHAR(64) PRIMARY KEY COMMENT '上下文检查点不透明编号，由服务端生成',
    owner_user_id BIGINT NOT NULL COMMENT '检查点所属用户编号，所有访问必须校验归属',
    conversation_id VARCHAR(64) NOT NULL COMMENT '绑定会话编号，每会话仅一个当前检查点',
    course_id BIGINT NOT NULL COMMENT '绑定课程编号，不允许跨课程恢复',
    revision BIGINT NOT NULL COMMENT '检查点发布版本，从一递增，用于比较交换',
    floor_seq BIGINT NOT NULL DEFAULT 0 COMMENT '明确放弃的原始前缀终点序号，零表示从会话起点覆盖',
    covered_from_seq BIGINT NOT NULL COMMENT '本轮滚动摘要最早覆盖的完整回合用户消息序号',
    covered_through_seq BIGINT NOT NULL COMMENT '最后覆盖的已完成助手消息序号，不能以消息数量代替',
    covered_input_hash CHAR(64) NOT NULL COMMENT '原始消息和依赖有序输入的滚动SHA-256摘要',
    summary_json MEDIUMTEXT NOT NULL COMMENT '有界语义摘要及任务状态JSON，不可信模型产物',
    dependencies_json MEDIUMTEXT NOT NULL COMMENT '整份摘要依赖的证据及偏好版本信封JSON，每次恢复复核',
    provenance_json MEDIUMTEXT NOT NULL COMMENT '摘要实际引用的消息身份证明及可选受限前缀屏障证明JSON',
    schema_version INT NOT NULL COMMENT '语义摘要结构版本，当前为一',
    prompt_version VARCHAR(120) NOT NULL COMMENT '摘要兼容提示协议版本，不含提示正文或凭据',
    model_identity VARCHAR(120) NOT NULL COMMENT '生成摘要的模型身份，不含密钥',
    source_run_id VARCHAR(64) NOT NULL COMMENT '在同一事务成功提交该检查点的运行编号',
    gap_reason VARCHAR(64) NOT NULL COMMENT '前缀放弃原因：NONE无缺口、RESTRICTED_PREFIX受限前缀',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标记：零正常，一已删除',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，修改时自动更新，精确到毫秒',
    UNIQUE KEY uk_assistant_context_conversation (conversation_id),
    KEY idx_assistant_context_owner_course (owner_user_id, course_id, deleted)
) COMMENT='学习助理可失效上下文检查点表';
