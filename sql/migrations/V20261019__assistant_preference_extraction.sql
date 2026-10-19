-- 自动提取默认关闭；run唯一领取，任何既有状态都不重放模型。CLAIMED/RESERVED中断视为未知。
CREATE TABLE IF NOT EXISTS assistant_preference_extraction_attempt (
    run_id VARCHAR(64) PRIMARY KEY COMMENT '所属助理运行编号，一次运行最多领取一次提取，重启不重放',
    owner_user_id BIGINT NOT NULL COMMENT '提取所属用户编号，服务端从当前运行身份确定',
    conversation_id VARCHAR(64) NOT NULL COMMENT '来源会话编号，领取时复核所有者、正常状态和运行租约',
    status VARCHAR(20) NOT NULL COMMENT '状态：CLAIMED已领取、RESERVED已预留、ACCEPTED已建偏好、NO_MATCH无结果、REJECTED已知计费拒绝、UNKNOWN未知、BUDGET_REJECTED预算拒绝',
    estimated_input_tokens INT NOT NULL COMMENT '保守输入Token估计，采用UTF8字节加固定协议开销',
    reserved_tokens INT NOT NULL DEFAULT 0 COMMENT '已记录的共享额度预留Token数；领取后崩溃可能预留状态未知，不得据0退款',
    actual_prompt_tokens INT NULL COMMENT '供应商明确报告的输入Token数，未知保持NULL',
    actual_completion_tokens INT NULL COMMENT '供应商明确报告的输出Token数，未知保持NULL',
    actual_total_tokens INT NULL COMMENT '供应商明确报告的总Token数，未知保持NULL',
    model_name VARCHAR(80) NOT NULL COMMENT '本次提取的固定供应商模型名称',
    prompt_version VARCHAR(80) NOT NULL COMMENT '提取提示与严格字面子串验证策略版本，不保存提示正文',
    preference_id CHAR(36) NULL COMMENT '成功创建的偏好编号，创建成功但审计失败时可能保持NULL，禁止重新提取',
    error_code VARCHAR(64) NULL COMMENT '固定安全错误码，不含原用户消息、模型正文或异常详情',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '领取时间，精确到毫秒',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '审计最后更新时间，精确到毫秒',
    KEY idx_extraction_owner (owner_user_id, created_at),
    KEY idx_extraction_status (status, updated_at)
) COMMENT='学习偏好自动提取审计表，模型调用最多一次且不保存原文';
