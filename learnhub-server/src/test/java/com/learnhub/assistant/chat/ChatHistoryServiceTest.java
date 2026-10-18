package com.learnhub.assistant.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.knowledge.*;
import com.learnhub.assistant.qa.*;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatHistoryServiceTest {
    final ChatPersistenceService persistence = mock(ChatPersistenceService.class);
    final KnowledgeStore store = mock(KnowledgeStore.class);
    final CourseService courses = mock(CourseService.class);
    final CourseQaService qa = mock(CourseQaService.class);
    final ObjectMapper mapper = new ObjectMapper();
    final KnowledgeRetrievalService.Source source = new KnowledgeRetrievalService.Source("9", 1, "11", "1", "2",
            "Redis", "原子性", 0, "原始课程正文", 1);
    final CurrentKnowledgeChunk chunk = new CurrentKnowledgeChunk(11, 9, 1, 1, 2, "Redis", 0, "原子性", "原始课程正文");
    ChatHistoryService history;

    @BeforeEach void setup() {
        history = new ChatHistoryService(persistence, courses, qa, mapper);
        when(qa.evidenceBook(7L, 1)).thenAnswer(call -> new EvidenceBook(7L, 1, store, courses, mapper));
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L));
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(chunk));
    }

    @Test void validHistoryReturnsVerifiedSources() throws Exception {
        var result = history.verify(7, 1, message(mapper.writeValueAsString(List.of(source))));
        assertThat(result.content()).isEqualTo("整条历史答案");
        assertThat(result.restricted()).isFalse();
        assertThat(result.sources()).singleElement().satisfies(citation -> assertThat(citation.chunkId()).isEqualTo("11"));
    }

    @Test void revokedAccessHidesEntireAnswerAndAllSources() throws Exception {
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of());
        assertHidden(history.verify(7, 1, message(mapper.writeValueAsString(List.of(source)))));
    }

    @Test void changedMaterialVersionHidesEntireAnswer() throws Exception {
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(
                new CurrentKnowledgeChunk(11, 9, 2, 1, 2, "Redis", 0, "原子性", "新版本正文")));
        assertHidden(history.verify(7, 1, message(mapper.writeValueAsString(List.of(source)))));
    }

    @Test void malformedOrStructuredToolHistoryCannotBeReplayed() {
        assertHidden(history.verify(7, 1, message("[{\"kind\":\"PROGRESS\",\"snapshot\":{}}]")));
        assertHidden(history.verify(7, 1, message("not json")));
    }

    @Test void ownershipAndCourseChecksPrecedeReadingMessages() {
        when(persistence.conversation(7, "foreign")).thenThrow(new BusinessException("CHAT_NOT_FOUND", "会话不存在"));
        assertThatThrownBy(() -> history.messages(7, "foreign", Long.MAX_VALUE, 20)).isInstanceOf(BusinessException.class);
        verify(persistence, never()).messages(anyLong(), anyString(), anyLong(), anyInt());
    }

    private static ChatPersistenceService.Message message(String sources) {
        return new ChatPersistenceService.Message("m", 2, "ASSISTANT", "整条历史答案", "COMPLETED", "r", sources);
    }
    private static void assertHidden(ChatHistoryService.HistoryMessage message) {
        assertThat(message.restricted()).isTrue();
        assertThat(message.content()).isEmpty();
        assertThat(message.sources()).isEmpty();
    }
}
