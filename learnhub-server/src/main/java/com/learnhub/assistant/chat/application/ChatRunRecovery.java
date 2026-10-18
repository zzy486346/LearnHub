package com.learnhub.assistant.chat.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 崩溃遗留运行仅改终态，绝不自动重试供应商请求。 */
@Component
@ConditionalOnProperty(prefix = "learnhub.assistant", name = "chat-enabled", havingValue = "true")
public class ChatRunRecovery {
    private final ChatPersistenceService persistence;
    public ChatRunRecovery(ChatPersistenceService persistence) { this.persistence = persistence; }

    @Scheduled(fixedDelay = 30_000)
    public void recover() { persistence.recoverExpired(); }
}
