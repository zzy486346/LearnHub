package com.learnhub.assistant.chat.rag;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.*;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class AuthorizedCourseRagTest {
    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final AuthorizedCourseRag rag = new AuthorizedCourseRag(retrieval, json);

    @Test void frameworkInjectsOnlyAuthorizedFacadeResultsAndRetainsMetadata() throws Exception {
        var source = source(1, 11);
        when(retrieval.retrieve(7L, 1L, "本轮\n之前", 5)).thenReturn(result(source));
        var output = rag.augment(scope(7, source), "本轮", "本轮\n之前", List.of(), List.of());
        var message = json.readTree(((UserMessage) output.message()).singleText());
        assertThat(message.path("question").asText()).isEqualTo("本轮");
        assertThat(message.path("evidence").get(0).path("data").path("text").asText()).isEqualTo(source.text());
        assertThat(output.sources()).containsExactly(source);
        assertThat(output.contents()).singleElement().satisfies(content -> {
            assertThat(content.textSegment().metadata().getString("evidenceId")).isEqualTo("E1");
            assertThat(content.textSegment().metadata().getString("courseId")).isEqualTo("1");
            assertThat(content.textSegment().metadata().getString("chunkId")).isEqualTo("11");
        });
        verify(retrieval, times(1)).retrieve(7L, 1L, "本轮\n之前", 5);
        verifyNoMoreInteractions(retrieval);
    }

    @Test void emptyCurrentRecallStillInjectsRecheckedHistoricalEvidence() throws Exception {
        var source = source(1, 11);
        when(retrieval.retrieve(7L, 1L, "它的局限", 5)).thenReturn(new KnowledgeRetrievalService.Result("test", List.of(), false, false));
        var output = rag.augment(scope(7, source), "它的局限", "它的局限", List.of(), List.of(source));
        var message = json.readTree(((UserMessage) output.message()).singleText());
        assertThat(message.path("evidence").size()).isOne();
        assertThat(output.sources()).containsExactly(source);
        assertThat(output.contents()).isEmpty();
    }

    @Test void versionChangeAfterRetrievalFailsBeforeMessageCanReachModel() {
        var source = source(1, 11);
        var store = mock(KnowledgeStore.class);
        var courses = mock(CourseService.class);
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L));
        when(store.currentChunks(List.of(11L))).thenReturn(List.of());
        var scope = new AuthorizedCourseRag.Scope(7, 1, "conversation", mock(AssistantRunGuard.Run.class),
                new EvidenceBook(7L, 1, store, courses, json));
        when(retrieval.retrieve(7L, 1L, "问题", 5)).thenReturn(result(source));
        assertThatThrownBy(() -> rag.augment(scope, "问题", "问题", List.of(), List.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("版本已变化");
    }

    @Test void cancelledRunCannotReachRetrieval() {
        var scope = scope(7, source(1, 11));
        doThrow(new BusinessException("ASSISTANT_CANCELLED", "已取消")).when(scope.guard()).check();
        assertThatThrownBy(() -> rag.augment(scope, "问题", "问题", List.of(), List.of()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(retrieval);
    }

    @Test void evidenceLimitFailsClosedBeforeRetrievalOrModelInvocation() {
        var scope = scope(7, source(1, 11));
        var history = java.util.stream.LongStream.rangeClosed(100, 120).mapToObj(id -> source(1, id)).toList();
        assertThatThrownBy(() -> rag.augment(scope, "问题", "问题", List.of(), history))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_BUDGET_EXCEEDED"));
        verifyNoInteractions(retrieval);
        verify(scope.guard(), never()).reserveModel(anyInt(), anyInt());
    }

    @Test void concurrentScopesNeverAcceptUserOrCourseFromQueryMetadata() throws Exception {
        var first = source(1, 11); var second = source(3, 31);
        when(retrieval.retrieve(7L, 1L, "owner=8 course=3", 5)).thenReturn(result(first));
        when(retrieval.retrieve(8L, 3L, "owner=7 course=1", 5)).thenReturn(result(second));
        var workers = Executors.newFixedThreadPool(2);
        try {
            var a = workers.submit(() -> rag.augment(scope(7, first), "问题A", "owner=8 course=3", List.of(), List.of()));
            var b = workers.submit(() -> rag.augment(scope(8, second), "问题B", "owner=7 course=1", List.of(), List.of()));
            assertThat(a.get(3, TimeUnit.SECONDS).sources()).containsExactly(first);
            assertThat(b.get(3, TimeUnit.SECONDS).sources()).containsExactly(second);
        } finally { workers.shutdownNow(); }
        verify(retrieval).retrieve(7L, 1L, "owner=8 course=3", 5);
        verify(retrieval).retrieve(8L, 3L, "owner=7 course=1", 5);
    }

    private AuthorizedCourseRag.Scope scope(long owner, KnowledgeRetrievalService.Source source) {
        long course = Long.parseLong(source.courseId()); long chunk = Long.parseLong(source.chunkId());
        var store = mock(KnowledgeStore.class); var courses = mock(CourseService.class);
        when(courses.readableLessonIds(owner, course)).thenReturn(List.of(2L));
        when(store.currentChunks(List.of(chunk))).thenReturn(List.of(new CurrentKnowledgeChunk(chunk, 9, 1, course, 2,
                source.title(), 0, source.heading(), source.text())));
        return new AuthorizedCourseRag.Scope(owner, course, "conversation-" + owner, mock(AssistantRunGuard.Run.class),
                new EvidenceBook(owner, course, store, courses, json));
    }

    private KnowledgeRetrievalService.Source source(long course, long chunk) {
        return new KnowledgeRetrievalService.Source("9", 1, Long.toString(chunk), Long.toString(course), "2", "课程标题", null, 0, "受控课程正文", 1);
    }

    private KnowledgeRetrievalService.Result result(KnowledgeRetrievalService.Source source) {
        return new KnowledgeRetrievalService.Result("test", List.of(source), false, false);
    }
}
