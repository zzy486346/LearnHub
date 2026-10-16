package com.learnhub.assistant.qa;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.*;
import com.learnhub.assistant.model.*;
import com.learnhub.assistant.runtime.*;
import com.learnhub.course.service.CourseService;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CourseQaServiceTest {
    final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    final KnowledgeStore store = mock(KnowledgeStore.class);
    final CourseService courses = mock(CourseService.class);
    final ChatModelClient client = mock(ChatModelClient.class);
    final AssistantRunGuard.Run run = mock(AssistantRunGuard.Run.class);
    final ObjectMapper mapper = new ObjectMapper();
    final KnowledgeRetrievalService.Source source = new KnowledgeRetrievalService.Source("9", 1, "11", "1", "2",
            "Redis课程", "Lua", 0, "Lua脚本在Redis执行期间不会被其他命令插入。", 1.0);
    final CurrentKnowledgeChunk chunk = new CurrentKnowledgeChunk(11, 9, 1, 1, 2, "Redis课程", 0,
            "Lua", "Lua脚本在Redis执行期间不会被其他命令插入。");
    CourseQaService qa;
    @BeforeEach void setup() {
        when(run.remaining()).thenReturn(Duration.ofSeconds(5));
        when(run.requestId()).thenReturn("test-run");
        when(run.summary()).thenReturn(new AssistantRunSummary("test-run", 1, 0, 0, 2000, 10, 10, 20, true, "SUCCEEDED", 1));
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L));
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(chunk));
        when(retrieval.retrieve(7L, 1, "Lua", 5)).thenReturn(new KnowledgeRetrievalService.Result("retrieval", List.of(source), false, false));
        var properties = new AiProperties(); properties.setMode("FAKE");
        qa = new CourseQaService(retrieval, store, courses, mapper,
                new AssistantModelInvoker(client, mapper, new AssistantLimits(), properties), new GroundedAnswers(mapper));
        when(client.generate(any(ChatRequest.class))).thenReturn(response(valid()));
    }

    @Test void returnsOnlyRegisteredCitationsAndStringIds() {
        var result = qa.answer(7L, 1, "Lua", run);
        assertThat(result.insufficientEvidence()).isFalse();
        assertThat(result.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.chunkId()).isEqualTo("11");
            assertThat(citation.evidenceId()).isEqualTo("E1");
            assertThat(citation.lessonId()).isEqualTo("2");
        });
        verify(run).reserveModel(anyInt(), eq(1024));
        verify(run).recordUsage(10, 10, 20);
    }
    @Test void noMaterialsDoesNotCallModel() {
        when(retrieval.retrieve(7L, 1, "Lua", 5)).thenReturn(new KnowledgeRetrievalService.Result("r", List.of(), true, false));
        assertThat(qa.answer(7L, 1, "Lua", run).insufficientEvidence()).isTrue();
        verifyNoInteractions(client);
    }
    @Test void revocationBeforeSendDoesNotLeakIntoModel() {
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of());
        assertThatThrownBy(() -> qa.answer(7L, 1, "Lua", run)).hasMessageContaining("已废弃");
        verifyNoInteractions(client);
    }
    @Test void materialChangeDuringGenerationDiscardsEntireAnswer() {
        when(client.generate(any(ChatRequest.class))).thenAnswer(invocation -> {
            when(store.currentChunks(List.of(11L))).thenReturn(List.of());
            return response(valid());
        });
        assertThatThrownBy(() -> qa.answer(7L, 1, "Lua", run)).hasMessageContaining("已废弃");
    }
    @Test void revocationWhileReservingBudgetPreventsSendingOldBody() {
        doAnswer(invocation -> {
            when(store.currentChunks(List.of(11L))).thenReturn(List.of()); return null;
        }).when(run).reserveModel(anyInt(), anyInt());
        assertThatThrownBy(() -> qa.answer(7L, 1, "Lua", run)).hasMessageContaining("已废弃");
        verifyNoInteractions(client);
    }
    @Test void logoutDuringGenerationDiscardsAnswer() {
        when(client.generate(any(ChatRequest.class))).thenAnswer(invocation -> {
            doThrow(new com.learnhub.common.exception.BusinessException("ASSISTANT_SESSION_EXPIRED", "登录状态失效")).when(run).check();
            return response(valid());
        });
        assertThatThrownBy(() -> qa.answer(7L, 1, "Lua", run)).hasMessageContaining("登录状态");
    }
    @ParameterizedTest @ValueSource(strings = {
            "{}", "{\"insufficientEvidence\":false,\"answerSegments\":[]}",
            "{\"insufficientEvidence\":true,\"answerSegments\":[],\"url\":\"https://evil.invalid\"}",
            "{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"秘密\",\"evidenceIds\":[\"E99\"],\"kind\":\"FACT\"}]}",
            "{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"秘密\",\"evidenceIds\":[],\"kind\":\"FACT\"}]}",
            "{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"已学完\",\"evidenceIds\":[\"E1\"],\"kind\":\"SUGGESTION\"}]}",
            "{\"insufficientEvidence\":true,\"answerSegments\":[]} {}",
            "{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"访问 https://evil.invalid\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}]}"
    }) void rejectsInvalidSchemaAndInventedReferences(String output) {
        when(client.generate(any(ChatRequest.class))).thenReturn(response(output));
        assertThatThrownBy(() -> qa.answer(7L, 1, "Lua", run)).hasMessageContaining("未通过校验");
    }
    @Test void sourceInstructionsStayInsideUntrustedUserData() {
        qa.answer(7L, 1, "Lua", run);
        var argument = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(client).generate(argument.capture());
        assertThat(argument.getValue().messages().get(0).role()).isEqualTo("system");
        assertThat(argument.getValue().messages().get(1).content()).contains("Lua脚本", "evidenceId");
        assertThat(argument.getValue().tools()).isEmpty();
    }
    @Test void invalidQuestionRejectedBeforeRetrieval() {
        assertThatThrownBy(() -> qa.answer(7L, 1, "x".repeat(501), run)).hasMessageContaining("500");
        verifyNoInteractions(retrieval, client);
    }
    static String valid() { return "{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"Lua执行期间不被其他命令插入。\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}]}"; }
    static ChatResponse response(String output) { return new ChatResponse(output, List.of(), new Usage(10, 10, 20), "stop", "MOCK"); }
}
