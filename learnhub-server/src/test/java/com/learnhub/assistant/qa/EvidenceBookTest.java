package com.learnhub.assistant.qa;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.*;
import com.learnhub.assistant.memory.PreferenceSnapshot;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EvidenceBookTest {
    @Test void preferencesHaveIndependentBoundedContextAndCannotBeCitedAsEvidence() {
        var book = new EvidenceBook(7L, 1, mock(KnowledgeStore.class), mock(CourseService.class), new ObjectMapper());
        var preference = preference("先看例子");
        book.registerPreference(preference, () -> preference);
        book.registerPreference(preference, () -> preference);
        assertThat(book.preferenceContext()).containsExactly(preference);
        assertThat(book.exportPreferenceDependencies()).containsExactly(preference);
        assertThat(book.context()).isEmpty();
        assertThat(book.references()).isEmpty();
        assertThat(book.exportDependencies()).isEmpty();
        assertThat(book.contains("E1")).isFalse();
        assertThatThrownBy(() -> book.citation("E1")).isInstanceOf(BusinessException.class);
        assertThat(book.material(source(1))).isEqualTo("E1");
        for (int i = 1; i < 10; i++) {
            var next = preference("例子" + i);
            book.registerPreference(next, () -> next);
        }
        var eleventh = preference("超出上限");
        assertThatThrownBy(() -> book.registerPreference(eleventh, () -> eleventh))
                .isInstanceOf(BusinessException.class);
        assertThat(book.preferenceContext()).hasSize(10);
    }

    @Test void changedForgottenOrUnavailablePreferenceInvalidatesWholeBookPermanently() {
        for (boolean unavailable : List.of(false, true)) {
            var book = new EvidenceBook(7L, 1, mock(KnowledgeStore.class), mock(CourseService.class), new ObjectMapper());
            var preference = preference("每晚二十分钟");
            var current = new AtomicReference<>(preference);
            book.registerPreference(preference, () -> {
                if (unavailable && current.get() == null) throw new IllegalStateException("down");
                return current.get();
            });
            assertThatCode(book::check).doesNotThrowAnyException();
            current.set(null);
            assertThatThrownBy(book::check).isInstanceOf(BusinessException.class);
            current.set(preference);
            assertThatThrownBy(book::check).isInstanceOf(BusinessException.class);
        }
    }

    @Test void preferenceSnapshotIdentityVersionCategoryAndTextAreValidated() {
        var book = new EvidenceBook(7L, 1, mock(KnowledgeStore.class), mock(CourseService.class), new ObjectMapper());
        String id = UUID.randomUUID().toString();
        for (var malformed : List.of(new PreferenceSnapshot("E1", 1, "STYLE", "例子"),
                new PreferenceSnapshot(id, 0, "STYLE", "例子"),
                new PreferenceSnapshot(id, 1, "SYSTEM", "例子"),
                new PreferenceSnapshot(id, 1, "STYLE", " "),
                new PreferenceSnapshot(id, 1, "STYLE", "字".repeat(201)),
                new PreferenceSnapshot(id, 1, "STYLE", "\uD800"))) {
            assertThatThrownBy(() -> book.registerPreference(malformed, () -> malformed))
                    .isInstanceOf(BusinessException.class);
        }
        var snapshot = preference("例子");
        assertThatThrownBy(() -> book.registerPreference(snapshot, null)).isInstanceOf(BusinessException.class);
    }

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
    private PreferenceSnapshot preference(String text) {
        return new PreferenceSnapshot(UUID.randomUUID().toString(), 1, "STYLE", text);
    }
}
