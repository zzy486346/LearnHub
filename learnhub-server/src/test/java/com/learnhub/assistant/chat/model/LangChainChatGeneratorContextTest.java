package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatHistoryService;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.chat.application.ChatEvidenceCodec;
import com.learnhub.assistant.chat.application.ChatToolExecutionStore;
import com.learnhub.assistant.tool.AssistantTools;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.runtime.AssistantLimits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class LangChainChatGeneratorContextTest {
    @Test
    void conditionalServiceUsesTheProductionConstructorInARealSpringContext() {
        new ApplicationContextRunner()
                .withPropertyValues("learnhub.assistant.langchain-enabled=true")
                .withBean(ChatPersistenceService.class, () -> mock(ChatPersistenceService.class))
                .withBean(ChatHistoryService.class, () -> mock(ChatHistoryService.class))
                .withBean(KnowledgeRetrievalService.class, () -> mock(KnowledgeRetrievalService.class))
                .withBean(CourseQaService.class, () -> mock(CourseQaService.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AiProperties.class, AiProperties::new)
                .withBean(AssistantLimits.class, AssistantLimits::new)
                .withBean(AssistantTools.class, () -> mock(AssistantTools.class))
                .withBean(ChatEvidenceCodec.class, () -> mock(ChatEvidenceCodec.class))
                .withBean(ChatToolExecutionStore.class, () -> mock(ChatToolExecutionStore.class))
                .withUserConfiguration(LangChainChatGenerator.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LangChainChatGenerator.class);
                });
    }
}
