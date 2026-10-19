package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learnhub.assistant.knowledge.vector.KnowledgeVectorSearch;
import com.learnhub.course.service.CourseService;
import com.learnhub.search.SearchUnavailableException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeHybridRetrievalTest {
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final KnowledgeSearchGateway bm25 = mock(KnowledgeSearchGateway.class);
    private final KnowledgeVectorSearch vector = mock(KnowledgeVectorSearch.class);
    private final CourseService courses = mock(CourseService.class);
    private KnowledgeRetrievalService service;
    private final KnowledgeSearchGateway.Hit hit = new KnowledgeSearchGateway.Hit(30, 10, 2, 0.9);

    @BeforeEach @SuppressWarnings("unchecked") void initialize() {
        ObjectProvider<KnowledgeVectorSearch> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vector);
        when(vector.hybridEnabled()).thenReturn(true);
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(20L));
        when(store.currentChunks(List.of(30L))).thenReturn(List.of(
                new CurrentKnowledgeChunk(30, 10, 2, 1, 20, "材料", 0, "小节", "MySQL当前正文")));
        when(bm25.search(1, List.of(20L), "课程", 100)).thenReturn(List.of(hit));
        when(vector.search(1, List.of(20L), "课程", 100)).thenReturn(List.of(hit));
        service = new KnowledgeRetrievalService(store, bm25, courses, provider);
    }

    @Test void fusionReturnsOnlyCurrentMysqlTextAndReportsRankScore() {
        var result = service.retrieve(7L, 1, "课程", 5);
        assertThat(result.retrievalMode()).isEqualTo("HYBRID");
        assertThat(result.sources()).singleElement().satisfies(source -> {
            assertThat(source.text()).isEqualTo("MySQL当前正文");
            assertThat(source.score()).isCloseTo(2.0 / 61, within(0.000001));
        });
    }

    @Test void pgFailureFallsBackToBm25AndKillSwitchSkipsVector() {
        when(vector.search(1, List.of(20L), "课程", 100)).thenThrow(new IllegalStateException("private detail"));
        assertThat(service.retrieve(7L, 1, "课程", 5).retrievalMode()).isEqualTo("BM25_FALLBACK");
        clearInvocations(vector);
        when(vector.hybridEnabled()).thenReturn(false);
        assertThat(service.retrieve(7L, 1, "课程", 5).retrievalMode()).isEqualTo("BM25");
        verify(vector, never()).search(anyLong(), anyList(), anyString(), anyInt());
    }

    @Test void esFailureCannotTreatPartialVectorBackfillAsInsufficientEvidence() {
        when(bm25.search(1, List.of(20L), "课程", 100)).thenThrow(new SearchUnavailableException(null));
        assertThatThrownBy(() -> service.retrieve(7L, 1, "课程", 5)).isInstanceOf(SearchUnavailableException.class)
                .hasMessageNotContaining("secret").hasCause(null);
        verify(vector, never()).search(anyLong(), anyList(), anyString(), anyInt());
    }

    @Test void refundOrVersionChangeAfterVectorSearchCannotReturnEvidence() {
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(20L), List.of());
        assertThat(service.retrieve(7L, 1, "课程", 5).sources()).isEmpty();
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(20L));
        when(store.currentChunks(List.of(30L))).thenReturn(List.of());
        assertThat(service.retrieve(7L, 1, "课程", 5).sources()).isEmpty();
    }

    @Test void conflictedProjectionIdentityCannotSubstituteCurrentDocument() {
        when(vector.search(1, List.of(20L), "课程", 100))
                .thenReturn(List.of(new KnowledgeSearchGateway.Hit(30, 99, 2, 0.99)));
        assertThat(service.retrieve(7L, 1, "课程", 5).sources()).isEmpty();
        verify(store).currentChunks(List.of());
    }
}
