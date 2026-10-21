package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learnhub.assistant.knowledge.vector.KnowledgeVectorSearch;
import com.learnhub.course.service.CourseService;
import com.learnhub.search.SearchUnavailableException;
import java.util.List;
import java.util.stream.Stream;
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

    @Test void rejectsWeakSemanticEvidenceEvenWhenBm25AndRrfRankItFirst() {
        when(bm25.search(1, List.of(20L), "课程", 100))
                .thenReturn(List.of(new KnowledgeSearchGateway.Hit(30, 10, 2, 10000)));
        when(vector.search(1, List.of(20L), "课程", 100))
                .thenReturn(List.of(new KnowledgeSearchGateway.Hit(30, 10, 2, 0.814999)));
        var result = service.retrieve(7L, 1, "课程", 5);
        assertThat(result.retrievalMode()).isEqualTo("HYBRID");
        assertThat(result.noRelevantMaterials()).isTrue();
        assertThat(result.sources()).isEmpty();
    }

    @Test void exactSemanticBoundaryPassesAndScoreRemainsTheOriginalRrfScore() {
        when(vector.search(1, List.of(20L), "课程", 100))
                .thenReturn(List.of(new KnowledgeSearchGateway.Hit(30, 10, 2, 0.815)));
        assertThat(service.retrieve(7L, 1, "课程", 5).sources()).singleElement()
                .satisfies(source -> assertThat(source.score()).isCloseTo(2.0 / 61, within(1e-9)));
    }

    @Test void removingFirstCandidateDoesNotPromoteRemainingCandidatesRrfRank() {
        var weak = new KnowledgeSearchGateway.Hit(30, 10, 2, 0.8);
        var strong = new KnowledgeSearchGateway.Hit(31, 11, 2, 0.83);
        when(bm25.search(1, List.of(20L), "课程", 100)).thenReturn(List.of(weak, strong));
        when(vector.search(1, List.of(20L), "课程", 100)).thenReturn(List.of(weak, strong));
        when(store.currentChunks(List.of(31L))).thenReturn(List.of(
                new CurrentKnowledgeChunk(31, 11, 2, 1, 20, "材料", 1, "小节", "有效正文")));
        assertThat(service.retrieve(7L, 1, "课程", 5).sources()).singleElement().satisfies(source -> {
            assertThat(source.chunkId()).isEqualTo("31");
            assertThat(source.score()).isCloseTo(2.0 / 62, within(1e-9));
        });
    }

    @Test void invalidSemanticScoresCannotBecomeEvidence() {
        for (double score : List.of(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 1.01)) {
            when(vector.search(1, List.of(20L), "课程", 100))
                    .thenReturn(List.of(new KnowledgeSearchGateway.Hit(30, 10, 2, score)));
            assertThat(service.retrieve(7L, 1, "课程", 5).sources()).isEmpty();
        }
    }

    @Test void bm25OnlyCandidateCannotBypassHybridSemanticThreshold() {
        when(vector.search(1, List.of(20L), "课程", 100)).thenReturn(List.of());
        assertThat(service.retrieve(7L, 1, "课程", 5).sources()).isEmpty();
        assertThat(withThreshold(0).retrieve(7L, 1, "课程", 5).sources()).hasSize(1);
    }

    @Test void strongSemanticOnlyCandidateCanPass() {
        when(bm25.search(1, List.of(20L), "课程", 100)).thenReturn(List.of());
        assertThat(service.retrieve(7L, 1, "课程", 5).sources()).hasSize(1);
    }

    @Test void filteringDoesNotHideThatTheCandidateLimitWasReached() {
        var hundred = java.util.stream.LongStream.range(30, 130).mapToObj(id ->
                new KnowledgeSearchGateway.Hit(id, 10, 2, id == 30 ? 0.9 : 0.8)).toList();
        when(bm25.search(1, List.of(20L), "课程", 100)).thenReturn(hundred);
        when(vector.search(1, List.of(20L), "课程", 100)).thenReturn(hundred);
        var result = service.retrieve(7L, 1, "课程", 5);
        assertThat(result.sources()).hasSize(1);
        assertThat(result.candidateLimitReached()).isTrue();
    }

    @Test void weakBm25RemainsAvailableWhenVectorIsDisabledOrUnavailable() {
        when(bm25.search(1, List.of(20L), "课程", 100))
                .thenReturn(List.of(new KnowledgeSearchGateway.Hit(30, 10, 2, 0.01)));
        when(vector.hybridEnabled()).thenReturn(false);
        assertThat(service.retrieve(7L, 1, "课程", 5).sources()).hasSize(1);
        when(vector.hybridEnabled()).thenReturn(true);
        when(vector.search(1, List.of(20L), "课程", 100)).thenThrow(new IllegalStateException());
        var fallback = service.retrieve(7L, 1, "课程", 5);
        assertThat(fallback.retrievalMode()).isEqualTo("BM25_FALLBACK");
        assertThat(fallback.sources()).hasSize(1);
    }

    @Test void rejectsInvalidThresholdConfiguration() {
        Stream.of(-0.1, 1.01, Double.NaN, Double.POSITIVE_INFINITY).forEach(threshold ->
                assertThatThrownBy(() -> withThreshold(threshold)).isInstanceOf(IllegalArgumentException.class));
    }

    @SuppressWarnings("unchecked") private KnowledgeRetrievalService withThreshold(double threshold) {
        ObjectProvider<KnowledgeVectorSearch> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vector);
        return new KnowledgeRetrievalService(store, bm25, courses, provider, threshold);
    }
}
