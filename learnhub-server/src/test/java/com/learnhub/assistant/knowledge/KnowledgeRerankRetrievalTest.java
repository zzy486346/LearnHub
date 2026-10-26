package com.learnhub.assistant.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learnhub.assistant.knowledge.rerank.KnowledgeReranker;
import com.learnhub.assistant.knowledge.vector.KnowledgeVectorSearch;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeRerankRetrievalTest {
    private static final long USER_ID = 7;
    private static final long COURSE_ID = 1;
    private static final String QUESTION = "哪份资料最相关";

    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final KnowledgeSearchGateway bm25 = mock(KnowledgeSearchGateway.class);
    private final KnowledgeVectorSearch vector = mock(KnowledgeVectorSearch.class);
    private final CourseService courses = mock(CourseService.class);
    private final KnowledgeReranker reranker = mock(KnowledgeReranker.class);

    @Test
    void promotesAWindowCandidateBeyondTheOriginalTopFiveWithoutTrustingRemoteText() {
        List<CurrentKnowledgeChunk> chunks = LongStream.rangeClosed(1, 7).mapToObj(this::chunk).toList();
        arrangeBm25(chunks);
        when(reranker.candidateLimit()).thenReturn(6);
        when(reranker.rerank(QUESTION, chunks.subList(0, 6).stream()
                .map(CurrentKnowledgeChunk::content).toList())).thenReturn(List.of(
                ranked(0, 0.4), ranked(1, 0.3), ranked(2, 0.2), ranked(3, 0.1),
                ranked(4, 0.0), ranked(5, 0.99)));

        var result = service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 5);

        assertThat(chunkIds(result)).containsExactly("6", "1", "2", "3", "4");
        assertThat(result.rerankMode()).isEqualTo("APPLIED");
        assertThat(result.retrievalMode()).isEqualTo("BM25");
        assertThat(result.sources().get(0).text()).isEqualTo("MySQL正文-6");
        assertThat(result.sources().get(0).score()).isEqualTo(score(6));
    }

    @Test
    void uploadsOnlyCurrentAuthorizedDistinctChunksFromTheRequestedCourse() {
        CurrentKnowledgeChunk legalOne = chunk(1);
        CurrentKnowledgeChunk crossCourse = chunk(2, 9, 102, 2, "跨课程正文");
        CurrentKnowledgeChunk unreadable = chunk(3, COURSE_ID, 999, 2, "未授权正文");
        CurrentKnowledgeChunk stale = chunk(4);
        CurrentKnowledgeChunk legalTwo = chunk(5);
        List<KnowledgeSearchGateway.Hit> hits = List.of(
                hit(legalOne), hit(legalOne), hit(crossCourse), hit(unreadable),
                new KnowledgeSearchGateway.Hit(stale.id(), stale.documentId(), stale.sourceVersion() - 1, score(4)),
                hit(legalTwo));
        List<CurrentKnowledgeChunk> chunks = List.of(legalOne, crossCourse, unreadable, stale, legalTwo);
        when(courses.readableLessonIds(USER_ID, COURSE_ID)).thenReturn(
                List.of(101L, 102L, 104L, 105L), List.of(101L, 102L, 104L, 105L));
        when(bm25.search(COURSE_ID, List.of(101L, 102L, 104L, 105L), QUESTION, 100)).thenReturn(hits);
        when(store.currentChunks(anyList())).thenReturn(chunks);
        when(reranker.candidateLimit()).thenReturn(20);
        when(reranker.rerank(QUESTION, List.of("MySQL正文-1", "MySQL正文-5")))
                .thenReturn(List.of(ranked(1, 0.9), ranked(0, 0.8)));

        var result = service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 5);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> documents = ArgumentCaptor.forClass(List.class);
        verify(reranker).rerank(org.mockito.ArgumentMatchers.eq(QUESTION), documents.capture());
        assertThat(documents.getValue()).containsExactly("MySQL正文-1", "MySQL正文-5");
        assertThat(chunkIds(result)).containsExactly("5", "1");
    }

    @Test
    void revalidatesAfterRerankAndUsesUnchangedTailWhenWindowCandidatesBecomeInvalid() {
        List<CurrentKnowledgeChunk> initial = LongStream.rangeClosed(1, 6).mapToObj(this::chunk).toList();
        List<Long> initiallyReadable = initial.stream().map(CurrentKnowledgeChunk::lessonId).toList();
        List<Long> refreshedReadable = List.of(101L, 102L, 104L, 105L, 106L);
        List<CurrentKnowledgeChunk> refreshed = List.of(
                chunk(1, COURSE_ID, 101, 3, "已切版正文"),
                chunk(2, COURSE_ID, 102, 2, "正文已修改"),
                initial.get(2), initial.get(3), initial.get(4), initial.get(5));
        when(courses.readableLessonIds(USER_ID, COURSE_ID))
                .thenReturn(initiallyReadable, initiallyReadable, refreshedReadable);
        when(bm25.search(COURSE_ID, initiallyReadable, QUESTION, 100))
                .thenReturn(initial.stream().map(this::hit).toList());
        when(store.currentChunks(anyList())).thenReturn(initial, refreshed);
        when(reranker.candidateLimit()).thenReturn(4);
        when(reranker.rerank(QUESTION, List.of(
                "MySQL正文-1", "MySQL正文-2", "MySQL正文-3", "MySQL正文-4")))
                .thenReturn(List.of(ranked(2, 0.9), ranked(1, 0.8), ranked(0, 0.7), ranked(3, 0.6)));

        var result = service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 3);

        assertThat(chunkIds(result)).containsExactly("4", "5", "6");
        assertThat(result.sources()).extracting(KnowledgeRetrievalService.Source::text)
                .containsExactly("MySQL正文-4", "MySQL正文-5", "MySQL正文-6");
        assertThat(result.rerankMode()).isEqualTo("APPLIED");
    }

    @Test
    void ordinaryRerankerFailureKeepsOriginalBm25FallbackOrderAndScores() {
        List<CurrentKnowledgeChunk> chunks = LongStream.rangeClosed(1, 3).mapToObj(this::chunk).toList();
        arrangeHybrid(chunks);
        when(vector.search(COURSE_ID, lessonIds(chunks), QUESTION, 100))
                .thenThrow(new IllegalStateException("embedding unavailable"));
        when(reranker.candidateLimit()).thenReturn(20);
        when(reranker.rerank(QUESTION, chunks.stream().map(CurrentKnowledgeChunk::content).toList()))
                .thenThrow(new IllegalStateException("provider detail"));

        var result = service(reranker, vector).retrieve(USER_ID, COURSE_ID, QUESTION, 3);

        assertThat(chunkIds(result)).containsExactly("1", "2", "3");
        assertThat(result.sources()).extracting(KnowledgeRetrievalService.Source::score)
                .containsExactly(score(1), score(2), score(3));
        assertThat(result.retrievalMode()).isEqualTo("BM25_FALLBACK");
        assertThat(result.rerankMode()).isEqualTo("FALLBACK");
    }

    @Test
    void terminalRerankerFailuresPropagateInsteadOfFallingBack() {
        List<CurrentKnowledgeChunk> chunks = List.of(chunk(1), chunk(2));
        arrangeBm25(chunks);
        when(reranker.candidateLimit()).thenReturn(20);
        List<String> documents = chunks.stream().map(CurrentKnowledgeChunk::content).toList();

        for (String code : List.of("ASSISTANT_CANCELLED", "ASSISTANT_DEADLINE_EXCEEDED")) {
            doThrow(new BusinessException(code, "terminal")).when(reranker).rerank(QUESTION, documents);
            assertThatThrownBy(() -> service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 2))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo(code));
        }
    }

    @Test
    void rejectsIncompleteDuplicateOutOfRangeAndNonFiniteRankings() {
        List<CurrentKnowledgeChunk> chunks = List.of(chunk(1), chunk(2), chunk(3));
        arrangeBm25(chunks);
        when(reranker.candidateLimit()).thenReturn(20);
        List<String> documents = chunks.stream().map(CurrentKnowledgeChunk::content).toList();
        List<List<KnowledgeReranker.Ranked>> invalidResults = List.of(
                List.of(ranked(0, 1), ranked(1, 0.5)),
                List.of(ranked(0, 1), ranked(0, 0.5), ranked(2, 0.1)),
                List.of(ranked(0, 1), ranked(1, 0.5), ranked(3, 0.1)),
                List.of(ranked(0, 1), ranked(1, Double.NaN), ranked(2, 0.1)));

        for (List<KnowledgeReranker.Ranked> invalid : invalidResults) {
            when(reranker.rerank(QUESTION, documents)).thenReturn(invalid);
            var result = service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 3);
            assertThat(chunkIds(result)).containsExactly("1", "2", "3");
            assertThat(result.rerankMode()).isEqualTo("FALLBACK");
        }
    }

    @Test
    void equalRerankScoresUseOriginalCandidateOrderAsStableTieBreaker() {
        List<CurrentKnowledgeChunk> chunks = List.of(chunk(1), chunk(2), chunk(3));
        arrangeBm25(chunks);
        when(reranker.candidateLimit()).thenReturn(20);
        when(reranker.rerank(QUESTION, chunks.stream().map(CurrentKnowledgeChunk::content).toList()))
                .thenReturn(List.of(ranked(2, 0.5), ranked(1, 0.5), ranked(0, 0.5)));

        var result = service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 3);

        assertThat(chunkIds(result)).containsExactly("1", "2", "3");
        assertThat(result.rerankMode()).isEqualTo("APPLIED");
    }

    @Test
    void preservesHybridModeAndFusedScoresAfterReranking() {
        List<CurrentKnowledgeChunk> chunks = List.of(chunk(1), chunk(2));
        arrangeHybrid(chunks);
        when(vector.search(COURSE_ID, lessonIds(chunks), QUESTION, 100))
                .thenReturn(chunks.stream().map(this::hit).toList());
        when(reranker.candidateLimit()).thenReturn(20);
        when(reranker.rerank(QUESTION, chunks.stream().map(CurrentKnowledgeChunk::content).toList()))
                .thenReturn(List.of(ranked(1, 0.9), ranked(0, 0.1)));

        var result = service(reranker, vector).retrieve(USER_ID, COURSE_ID, QUESTION, 2);

        assertThat(result.retrievalMode()).isEqualTo("HYBRID");
        assertThat(chunkIds(result)).containsExactly("2", "1");
        assertThat(result.sources().get(0).score()).isCloseTo(2.0 / 62, within(1e-9));
        assertThat(result.sources().get(1).score()).isCloseTo(2.0 / 61, within(1e-9));
    }

    @Test
    void keepsCandidateLimitReachedAfterFilteringAndReranking() {
        List<KnowledgeSearchGateway.Hit> hits = LongStream.rangeClosed(1, 100)
                .mapToObj(id -> new KnowledgeSearchGateway.Hit(id, 1_000 + id, 2, score(id))).toList();
        List<CurrentKnowledgeChunk> chunks = LongStream.rangeClosed(1, 100).mapToObj(this::chunk).toList();
        when(courses.readableLessonIds(USER_ID, COURSE_ID)).thenReturn(lessonIds(chunks));
        when(bm25.search(COURSE_ID, lessonIds(chunks), QUESTION, 100)).thenReturn(hits);
        when(store.currentChunks(anyList())).thenReturn(chunks);
        when(reranker.candidateLimit()).thenReturn(2);
        when(reranker.rerank(QUESTION, List.of("MySQL正文-1", "MySQL正文-2")))
                .thenReturn(List.of(ranked(1, 0.9), ranked(0, 0.8)));

        var result = service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 2);

        assertThat(result.candidateLimitReached()).isTrue();
        assertThat(chunkIds(result)).containsExactly("2", "1");
    }

    @Test
    void zeroAndOneCandidateSkipRemoteRerankAndSecondRevalidation() {
        when(courses.readableLessonIds(USER_ID, COURSE_ID)).thenReturn(List.of(101L));
        when(bm25.search(COURSE_ID, List.of(101L), QUESTION, 100)).thenReturn(List.of());
        when(store.currentChunks(anyList())).thenReturn(List.of());
        var empty = service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 2);
        assertThat(empty.rerankMode()).isEqualTo("SKIPPED");

        CurrentKnowledgeChunk only = chunk(1);
        when(bm25.search(COURSE_ID, List.of(101L), QUESTION, 100)).thenReturn(List.of(hit(only)));
        when(store.currentChunks(anyList())).thenReturn(List.of(only));
        var single = service(reranker, null).retrieve(USER_ID, COURSE_ID, QUESTION, 2);
        assertThat(single.rerankMode()).isEqualTo("SKIPPED");
        assertThat(chunkIds(single)).containsExactly("1");

        verify(reranker, never()).rerank(org.mockito.ArgumentMatchers.anyString(), anyList());
        verify(store, org.mockito.Mockito.times(2)).currentChunks(anyList());
    }

    @Test
    void missingRerankerKeepsLegacyOrderWithoutExtraRevalidation() {
        List<CurrentKnowledgeChunk> chunks = List.of(chunk(1), chunk(2));
        arrangeBm25(chunks);

        var result = service(null, null).retrieve(USER_ID, COURSE_ID, QUESTION, 2);

        assertThat(chunkIds(result)).containsExactly("1", "2");
        assertThat(result.rerankMode()).isEqualTo("DISABLED");
        verify(store).currentChunks(anyList());
    }

    private void arrangeBm25(List<CurrentKnowledgeChunk> chunks) {
        List<Long> readable = lessonIds(chunks);
        when(courses.readableLessonIds(USER_ID, COURSE_ID)).thenReturn(readable);
        when(bm25.search(COURSE_ID, readable, QUESTION, 100))
                .thenReturn(chunks.stream().map(this::hit).toList());
        when(store.currentChunks(anyList())).thenReturn(chunks);
    }

    private void arrangeHybrid(List<CurrentKnowledgeChunk> chunks) {
        arrangeBm25(chunks);
        when(vector.hybridEnabled()).thenReturn(true);
    }

    private KnowledgeRetrievalService service(KnowledgeReranker selectedReranker,
                                              KnowledgeVectorSearch selectedVector) {
        return new KnowledgeRetrievalService(store, bm25, courses, provider(selectedVector), 0,
                provider(selectedReranker));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private CurrentKnowledgeChunk chunk(long id) {
        return chunk(id, COURSE_ID, 100 + id, 2, "MySQL正文-" + id);
    }

    private CurrentKnowledgeChunk chunk(long id, long courseId, long lessonId, long version, String content) {
        return new CurrentKnowledgeChunk(id, 1_000 + id, version, courseId, lessonId,
                "资料-" + id, (int) id, "小节-" + id, content);
    }

    private KnowledgeSearchGateway.Hit hit(CurrentKnowledgeChunk chunk) {
        return new KnowledgeSearchGateway.Hit(
                chunk.id(), chunk.documentId(), chunk.sourceVersion(), score(chunk.id()));
    }

    private KnowledgeReranker.Ranked ranked(int index, double score) {
        return new KnowledgeReranker.Ranked(index, score);
    }

    private double score(long id) {
        return 1.0 - id / 1_000.0;
    }

    private List<Long> lessonIds(List<CurrentKnowledgeChunk> chunks) {
        return chunks.stream().map(CurrentKnowledgeChunk::lessonId).toList();
    }

    private List<String> chunkIds(KnowledgeRetrievalService.Result result) {
        return result.sources().stream().map(KnowledgeRetrievalService.Source::chunkId).toList();
    }
}
