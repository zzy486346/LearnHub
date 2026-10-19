package com.learnhub.assistant.qa;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.*;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceBookTest {
    @Test void movingMaterialToAnotherReadableLessonInvalidatesOldCitation() {
        var store = mock(KnowledgeStore.class);
        var courses = mock(CourseService.class);
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L, 3L));
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(
                new CurrentKnowledgeChunk(11, 9, 1, 1, 3, "讲义", 0, "标题", "正文")));
        var book = new EvidenceBook(7L, 1, store, courses, new ObjectMapper());
        book.material(source(1));
        assertThatThrownBy(book::check).isInstanceOf(BusinessException.class)
                .hasMessageContaining("版本已变化");
    }

    @Test void retrievalScoreChangesDoNotInvalidateStableMaterial() {
        var store = mock(KnowledgeStore.class);
        var courses = mock(CourseService.class);
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L));
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(
                new CurrentKnowledgeChunk(11, 9, 1, 1, 2, "讲义", 0, "标题", "正文")));
        var book = new EvidenceBook(7L, 1, store, courses, new ObjectMapper());
        assertThat(book.material(source(1))).isEqualTo("E1");
        assertThat(book.material(source(8))).isEqualTo("E1");
        assertThatCode(book::check).doesNotThrowAnyException();
    }

    private KnowledgeRetrievalService.Source source(double score) {
        return new KnowledgeRetrievalService.Source("9", 1, "11", "1", "2", "讲义", "标题", 0, "正文", score);
    }
}
