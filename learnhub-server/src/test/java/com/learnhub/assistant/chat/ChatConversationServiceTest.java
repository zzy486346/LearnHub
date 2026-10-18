package com.learnhub.assistant.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.course.service.CourseService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatConversationServiceTest {
    final ChatPersistenceService persistence = mock(ChatPersistenceService.class);
    final ChatHistoryService history = mock(ChatHistoryService.class);
    final CourseService courses = mock(CourseService.class);
    final AssistantRunGuard.Run guard = mock(AssistantRunGuard.Run.class);
    final AiProperties properties = new AiProperties();
    final ChatConversationService service = new ChatConversationService(persistence, history, courses, properties);
    final ChatPersistenceService.Run stored = new ChatPersistenceService.Run("run", "conversation", 7, "request", "RUNNING", "lease", "它有什么局限？");

    @Test void fakeFollowupUsesPersistedPreviousQuestionAndGuard() {
        properties.setMode("FAKE");
        when(history.require(7, "conversation")).thenReturn(new ChatPersistenceService.Conversation("conversation", 7, 1, "Redis", 3));
        when(history.messages(7, "conversation", Long.MAX_VALUE, 20)).thenReturn(List.of(
                new ChatHistoryService.HistoryMessage("m", 1, "USER", "缓存击穿是什么？", "COMPLETED", "old", List.of(), false)));
        when(persistence.complete(eq(7L), eq("run"), eq("lease"), anyString(), eq("[]"), eq("{}"))).thenReturn(true);
        when(persistence.run(7, "run")).thenReturn(stored);
        String prepared = service.prepareFake(7, stored, guard);
        assertThat(prepared).contains("缓存击穿是什么？");
        verify(persistence, never()).complete(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(service.commitFake(7, stored, prepared).status()).isEqualTo("COMPLETED");
        verify(persistence).complete(eq(7L), eq("run"), eq("lease"), contains("缓存击穿是什么？"), eq("[]"), eq("{}"));
        verify(guard).reserveModel(anyInt(), eq(512));
        verify(guard).recordUsage(0, 0, 0);
    }

    @Test void realModeCannotAccidentallyUseFakeAndDoesNotReserveModel() {
        properties.setMode("DEEPSEEK");
        assertThatThrownBy(() -> service.prepareFake(7, stored, guard)).hasMessageContaining("暂仅开放模拟模式");
        verify(guard, never()).reserveModel(anyInt(), anyInt());
        verify(persistence).fail(7, "run", "FAILED", "AI_DISABLED");
    }

    @Test void lostLeaseCannotPersistSuccessfulReply() {
        properties.setMode("FAKE");
        when(history.require(7, "conversation")).thenReturn(new ChatPersistenceService.Conversation("conversation", 7, 1, "Redis", 1));
        when(history.messages(7, "conversation", Long.MAX_VALUE, 20)).thenReturn(List.of());
        String prepared = service.prepareFake(7, stored, guard);
        assertThatThrownBy(() -> service.commitFake(7, stored, prepared)).hasMessageContaining("已终止");
        verify(guard, never()).markSucceeded();
    }

    @Test void courseAccessIsCheckedBeforePersistence() {
        doThrow(new IllegalStateException("课程受限")).when(courses).requireAvailable(1L, 7L);
        assertThatThrownBy(() -> service.create(7, 1, "Redis")).hasMessage("课程受限");
        verifyNoInteractions(persistence);
        service.start(7, "conversation", "request", "hi", Duration.ofSeconds(45));
        verify(history).require(7, "conversation");
    }
}
