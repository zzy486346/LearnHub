package com.learnhub.assistant.agent;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.*;
import com.learnhub.assistant.model.*;
import com.learnhub.assistant.qa.*;
import com.learnhub.assistant.runtime.*;
import com.learnhub.assistant.tool.AssistantTools;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import com.learnhub.search.SearchUnavailableException;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CourseAgentServiceTest {
    final ObjectMapper mapper = new ObjectMapper();
    final KnowledgeStore store = mock(KnowledgeStore.class);
    final CourseService courses = mock(CourseService.class);
    final ChatModelClient client = mock(ChatModelClient.class);
    final AssistantTools tools = mock(AssistantTools.class);
    final AssistantRunGuard.Run run = mock(AssistantRunGuard.Run.class);
    CourseAgentService agent;
    @BeforeEach void setup() {
        when(run.remaining()).thenReturn(Duration.ofSeconds(10));
        when(run.requestId()).thenReturn("agent-test");
        when(run.summary()).thenReturn(new AssistantRunSummary("agent-test", 2, 1, 1, 1000, 20, 20, 40, true, "SUCCEEDED", 1));
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L));
        when(tools.definitions()).thenReturn(List.of(Map.of("type", "function", "function", Map.of("name", "get_course_outline"))));
        when(tools.execute(anyLong(), anyLong(), any(), any(), any())).thenReturn(Map.of("observations", List.of()));
        var properties = new AiProperties(); properties.setMode("FAKE");
        var invoker = new AssistantModelInvoker(client, mapper, new AssistantLimits(), properties);
        var answers = new GroundedAnswers(mapper);
        var qa = new CourseQaService(mock(KnowledgeRetrievalService.class), store, courses, mapper, invoker, answers);
        agent = new CourseAgentService(qa, invoker, tools, answers, mapper);
    }
    @Test void acceptsModelSelectedOrderAndPreservesCallIds() {
        var first = call("a", "get_my_learning_progress");
        var second = call("b", "get_course_outline");
        when(client.generate(any(ChatRequest.class))).thenReturn(toolResponse(first, second), insufficient());
        var answer = agent.answer(7L, 1, "下一步学什么", run);
        assertThat(answer.trace()).containsExactly("get_my_learning_progress", "get_course_outline");
        var order = inOrder(tools);
        order.verify(tools).definitions();
        order.verify(tools).execute(eq(7L), eq(1L), eq(first), any(), eq(run));
        order.verify(tools).execute(eq(7L), eq(1L), eq(second), any(), eq(run));
        var requests = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(client, times(2)).generate(requests.capture());
        assertThat(requests.getAllValues().get(1).messages()).filteredOn(m -> m.role().equals("tool"))
                .extracting(ChatMessage::toolCallId).containsExactly("a", "b");
        verify(run).toolDecisionRound();
    }
    @Test void anotherModelDecisionUsesDifferentToolSequence() {
        when(client.generate(any(ChatRequest.class))).thenReturn(toolResponse(call("search", "search_courses")), insufficient());
        assertThat(agent.answer(7L, 1, "找Redis课程", run).trace()).containsExactly("search_courses");
        verify(tools, never()).execute(anyLong(), anyLong(), argThat(call -> call.name().equals("get_my_learning_progress")), any(), any());
    }
    @Test void staleMaterialFromHistoricalToolMessageIsNeverSentOnNextRound() {
        var source = new KnowledgeRetrievalService.Source("9", 1, "11", "1", "2", "私有课时", "Lua", 0, "受控正文", 1);
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(new CurrentKnowledgeChunk(11, 9, 1, 1, 2, "私有课时", 0, "Lua", "受控正文")));
        when(tools.execute(anyLong(), anyLong(), any(), any(), any())).thenAnswer(invocation -> {
            EvidenceBook book = invocation.getArgument(3);
            return book.observation(book.material(source));
        });
        AtomicInteger reservations = new AtomicInteger();
        doAnswer(invocation -> {
            if (reservations.incrementAndGet() == 2) when(store.currentChunks(List.of(11L))).thenReturn(List.of());
            return null;
        }).when(run).reserveModel(anyInt(), anyInt());
        when(client.generate(any(ChatRequest.class))).thenReturn(toolResponse(call("m", "search_course_materials")), insufficient());
        assertThatThrownBy(() -> agent.answer(7L, 1, "解释Lua", run)).hasMessageContaining("已废弃");
        verify(client, times(1)).generate(any(ChatRequest.class));
    }
    @Test void recommendationsRequireRegisteredProgressAndOutlineSnapshots() {
        when(tools.execute(anyLong(), anyLong(), any(), any(), any())).thenAnswer(invocation -> {
            ToolCall call = invocation.getArgument(2); EvidenceBook book = invocation.getArgument(3);
            String kind = call.name().equals("get_course_outline") ? "OUTLINE" : "PROGRESS";
            var data = mapper.valueToTree(Map.of("courseId", "1", "title", kind, "records", List.of()));
            String id = book.register(kind, kind, data, () -> data);
            return book.observation(id);
        });
        var finalAnswer = new ChatResponse("{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"尚无学习记录，建议先学习第一课。\",\"evidenceIds\":[\"E1\",\"E2\"],\"kind\":\"SUGGESTION\"}]}",
                List.of(), new Usage(10, 10, 20), "stop", "MOCK");
        when(client.generate(any(ChatRequest.class))).thenReturn(toolResponse(call("o", "get_course_outline"), call("p", "get_my_learning_progress")), finalAnswer);
        var answer = agent.answer(7L, 1, "下一步学什么", run);
        assertThat(answer.citations()).extracting(EvidenceBook.Citation::kind).containsExactly("OUTLINE", "PROGRESS");
        assertThat(answer.answerSegments()).singleElement().satisfies(segment -> assertThat(segment.kind()).isEqualTo("SUGGESTION"));
    }
    @Test void duplicateCallIdTerminatesWithoutRepeatingTool() {
        var response = toolResponse(call("same", "get_course_outline"));
        when(client.generate(any(ChatRequest.class))).thenReturn(response, response);
        assertThatThrownBy(() -> agent.answer(7L, 1, "目录", run)).hasMessageContaining("重复");
        verify(tools, times(1)).execute(anyLong(), anyLong(), any(), any(), any());
    }
    @Test void toolFailureBecomesBoundedSanitizedObservation() {
        when(tools.execute(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new SearchUnavailableException(new IOException("private SQL secret=hidden")));
        when(client.generate(any(ChatRequest.class))).thenReturn(toolResponse(call("s", "search_courses")), insufficient());
        assertThat(agent.answer(7L, 1, "找课程", run).insufficientEvidence()).isTrue();
        var requests = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(client, times(2)).generate(requests.capture());
        assertThat(requests.getAllValues().get(1).messages().get(3).content())
                .contains("TOOL_UNAVAILABLE").doesNotContain("private", "secret", "SQL");
    }
    @Test void rejectedToolAndBudgetFailureStopFurtherModelCalls() {
        when(tools.execute(anyLong(), anyLong(), any(), any(), any()))
                .thenThrow(new BusinessException("ASSISTANT_TOOL_INVALID", "未知工具"));
        when(client.generate(any(ChatRequest.class))).thenReturn(toolResponse(call("x", "shell")));
        assertThatThrownBy(() -> agent.answer(7L, 1, "写操作", run)).hasMessageContaining("未知工具");
        verify(client, times(1)).generate(any(ChatRequest.class));
    }
    @Test void toolRoundBudgetIsCheckedBeforeExecution() {
        when(client.generate(any(ChatRequest.class))).thenReturn(toolResponse(call("o", "get_course_outline")));
        doThrow(new BusinessException("ASSISTANT_TOOL_LIMIT", "达到轮次上限")).when(run).toolDecisionRound();
        assertThatThrownBy(() -> agent.answer(7L, 1, "目录", run)).hasMessageContaining("轮次上限");
        verify(tools, never()).execute(anyLong(), anyLong(), any(), any(), any());
    }
    static ToolCall call(String id, String name) { return new ToolCall(id, name, "{\"courseId\":\"1\"}"); }
    static ChatResponse toolResponse(ToolCall... calls) { return new ChatResponse("", List.of(calls), new Usage(10, 10, 20), "tool_calls", "MOCK"); }
    static ChatResponse insufficient() { return new ChatResponse("{\"insufficientEvidence\":true,\"answerSegments\":[]}", List.of(), new Usage(10, 10, 20), "stop", "MOCK"); }
}
