package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.learnhub.course.service.CourseService;
import com.learnhub.common.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeRetrievalServiceTest {
    final KnowledgeStore store = mock(KnowledgeStore.class);
    final KnowledgeSearchGateway gateway = mock(KnowledgeSearchGateway.class);
    final CourseService courses = mock(CourseService.class);
    final KnowledgeRetrievalService service = new KnowledgeRetrievalService(store, gateway, courses);
    final KnowledgeSearchGateway.Hit hit = new KnowledgeSearchGateway.Hit(30L, 10L, 2L, 1.5);
    final CurrentKnowledgeChunk chunk = new CurrentKnowledgeChunk(30L, 10L, 2L, 1L, 20L,
            "Redis", 0, "原子性", "Lua 脚本将库存校验与预扣作为一次原子操作。");

    void candidates() {
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(20L));
        when(gateway.search(1L, List.of(20L), "Lua", 100)).thenReturn(List.of(hit));
        when(store.currentChunks(List.of(30L))).thenReturn(List.of(chunk));
    }

    @Test void returnsOnlyDatabaseContentWithStringIdsAndSourceVersion() {
        candidates();
        var result = service.retrieve(7L, 1L, " Lua ", 5);
        assertThat(result.noRelevantMaterials()).isFalse();
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo("30");
            assertThat(source.documentId()).isEqualTo("10");
            assertThat(source.sourceVersion()).isEqualTo(2L);
            assertThat(source.text()).isEqualTo(chunk.content());
        });
        verify(courses, times(2)).readableLessonIds(7L, 1L);
    }

    @Test void emptyAuthorizationSkipsElasticsearchAndDatabaseChunks() {
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of());
        assertThat(service.retrieve(7L, 1L, "Lua", 5).sources()).isEmpty();
        verifyNoInteractions(gateway, store);
    }

    @Test void refundBetweenSearchAndResponseRemovesPrivateText() {
        candidates();
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(20L), List.of());
        assertThat(service.retrieve(7L, 1L, "Lua", 5).sources()).isEmpty();
    }

    @Test void unavailableCourseDuringRecheckFailsClosed() {
        candidates();
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(20L))
                .thenThrow(new BusinessException("COURSE_NOT_FOUND", "课程不可访问"));
        assertThatThrownBy(() -> service.retrieve(7L, 1L, "Lua", 5)).hasMessage("课程不可访问");
        verifyNoInteractions(store);
    }

    @Test void deletedOrOldDatabaseVersionIsDiscarded() {
        candidates(); when(store.currentChunks(List.of(30L))).thenReturn(List.of());
        assertThat(service.retrieve(7L, 1L, "Lua", 5).noRelevantMaterials()).isTrue();
    }

    @Test void staleElasticsearchMetadataCannotSubstituteCurrentChunk() {
        candidates();
        when(gateway.search(1L, List.of(20L), "Lua", 100))
                .thenReturn(List.of(new KnowledgeSearchGateway.Hit(30L, 10L, 1L, 1)));
        assertThat(service.retrieve(7L, 1L, "Lua", 5).sources()).isEmpty();
    }

    @Test void wrongCourseAndDuplicateCandidatesAreNotReturned() {
        candidates();
        when(gateway.search(1L, List.of(20L), "Lua", 100)).thenReturn(List.of(hit, hit));
        assertThat(service.retrieve(7L, 1L, "Lua", 5).sources()).hasSize(1);
        when(store.currentChunks(List.of(30L))).thenReturn(List.of(new CurrentKnowledgeChunk(30L, 10L, 2L,
                99L, 20L, "错误课程", 0, "", "不应返回")));
        assertThat(service.retrieve(7L, 1L, "Lua", 5).sources()).isEmpty();
    }

    @Test void invalidInputRejectedBeforeAnyAccess() {
        assertThatThrownBy(() -> service.retrieve(7L, 1L, " ", 5)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.retrieve(7L, 1L, "Lua", 100)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(store, courses, gateway);
    }
}
