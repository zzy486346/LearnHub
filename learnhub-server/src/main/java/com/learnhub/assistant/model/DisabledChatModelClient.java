package com.learnhub.assistant.model;

import com.learnhub.common.exception.BusinessException;

final class DisabledChatModelClient implements ChatModelClient {
    @Override
    public ChatResponse generate(ChatRequest request) {
        throw new BusinessException("AI_DISABLED", "AI 模型功能尚未启用");
    }
}
