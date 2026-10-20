package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatEvidenceCodec;
import com.learnhub.assistant.chat.application.ChatHistoryService;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.chat.application.ChatToolExecutionStore;
import com.learnhub.assistant.knowledge.CurrentKnowledgeChunk;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.assistant.runtime.AssistantLimits;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.assistant.runtime.AssistantRunSummary;
import com.learnhub.assistant.tool.AssistantTools;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.model.CourseChapter;
import com.learnhub.course.model.CourseLesson;
import com.learnhub.course.model.LearningProgress;
import com.learnhub.course.service.CourseService;
import com.learnhub.course.service.LearningProgressService;
import com.learnhub.search.CourseSearchService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 所有 HTTP 均限制到本机随机端口，实际 SDK 与实际严格工具包装器共同运行。 */
@Timeout(15)
class LangChainToolChatGeneratorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatPersistenceService persistence = mock(ChatPersistenceService.class);
    private final CourseService courses = mock(CourseService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final CourseQaService qa = mock(CourseQaService.class);
    private final AssistantRunGuard.Run guard = mock(AssistantRunGuard.Run.class);
    private final LearningProgressService progress = mock(LearningProgressService.class);
    private final CourseChapterMapper chapters = mock(CourseChapterMapper.class);
    private final CourseLessonMapper lessons = mock(CourseLessonMapper.class);
    private final ChatToolExecutionStore executions = mock(ChatToolExecutionStore.class);
    private final ChatPersistenceService.Run stored = new ChatPersistenceService.Run(
            "tool-run", "tool-conversation", 7, "tool-request", "RUNNING", "test-lease", "根据我的进度给出学习建议");
    private final KnowledgeRetrievalService.Source source = new KnowledgeRetrievalService.Source(
            "9", 1, "11", "1", "2", "缓存课程", "互斥重建", 0, "互斥锁限制缓存并发重建。", 1);
    private AssistantTools tools;
    private ChatEvidenceCodec codec;
    private LangChainChatGenerator generator;
    private MockEndpoint server;
    private final AssistantLimits limits = new AssistantLimits();

    @BeforeEach void setup() throws Exception {
        server = new MockEndpoint();
        when(guard.remaining()).thenReturn(Duration.ofSeconds(8));
        when(guard.requestId()).thenReturn(stored.id());
        when(guard.summary()).thenReturn(new AssistantRunSummary(stored.id(), 2, 1, 2,
                10_000, 20, 10, 30, true, "RUNNING", 1));
        when(persistence.conversation(7, stored.conversationId())).thenReturn(
                new ChatPersistenceService.Conversation(stored.conversationId(), 7, 1, "课程", 1));
        when(persistence.messages(7, stored.conversationId(), Long.MAX_VALUE, 48)).thenReturn(List.of());
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L));
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(
                new CurrentKnowledgeChunk(11, 9, 1, 1, 2, source.title(), 0, source.heading(), source.text())));
        when(qa.evidenceBook(7L, 1L)).thenAnswer(ignored -> new EvidenceBook(7L, 1, store, courses, mapper));
        when(retrieval.retrieve(eq(7L), eq(1L), anyString(), anyInt())).thenReturn(
                new KnowledgeRetrievalService.Result("retrieval", List.of(source), false, false));
        CourseChapter chapter = new CourseChapter();
        chapter.setId(3L); chapter.setCourseId(1L); chapter.setTitle("缓存基础"); chapter.setSortOrder(1);
        CourseLesson lesson = new CourseLesson();
        lesson.setId(2L); lesson.setChapterId(3L); lesson.setTitle("互斥锁重建"); lesson.setSortOrder(1);
        lesson.setDurationSeconds(300); lesson.setFreePreview(true);
        when(chapters.selectList(any())).thenReturn(List.of(chapter));
        when(lessons.selectList(any())).thenReturn(List.of(lesson));
        LearningProgress row = new LearningProgress();
        row.setId(900L); row.setUserId(7L); row.setLessonId(2L); row.setPositionSeconds(30);
        row.setCompleted(false); row.setLastLearnedAt(LocalDateTime.of(2026, 10, 19, 12, 0));
        when(progress.getByCourse(7L, 1L)).thenReturn(List.of(row));
        when(executions.begin(eq(7L), eq(stored), anyInt(), any(ToolCall.class), anyString()))
                .thenAnswer(ignored -> UUID.randomUUID().toString());
        tools = new AssistantTools(mock(CourseSearchService.class), courses, retrieval, progress, chapters, lessons, mapper);
        codec = new ChatEvidenceCodec(tools);
        var history = new ChatHistoryService(persistence, courses, qa, mapper, codec);
        var properties = new AiProperties();
        properties.setMode("DEEPSEEK"); properties.setApiKey("local_mock_only"); properties.setModel("deepseek-flash");
        properties.setMaxOutputTokens(512); properties.setRequestTimeout(Duration.ofSeconds(8));
        limits.setMaxOutputTokens(512);
        generator = new LangChainChatGenerator(persistence, history, retrieval, qa, mapper,
                properties, limits, server.endpoint(), tools, codec, executions);
    }

    @AfterEach void cleanup() throws Exception {
        try { if (generator != null) generator.close(); }
        finally { if (server != null) server.close(); }
    }

    @Test void assistantInputLimitRejectsRequestBeforeReservationAndHttp() {
        limits.setMaxInputBytes(1);
        assertThatThrownBy(() -> prepare(ignored -> { }, ignored -> { }))
                .isInstanceOf(BusinessException.class)
                .extracting(failure -> ((BusinessException) failure).getCode()).isEqualTo("AI_MODEL_INPUT_TOO_LARGE");
        assertThat(server.requests).isEmpty();
        verify(guard, never()).reserveModel(anyInt(), anyInt());
    }

    @Test void secondModelRoundUsesRemainingReservationAndFailsBeforeAnotherHttp() {
        limits.setMaxRunTokens(12000);
        var reserved = new java.util.concurrent.atomic.AtomicLong();
        when(guard.summary()).thenAnswer(ignored -> new AssistantRunSummary(stored.id(), 0, 0, 0,
                reserved.get(), 0, 0, 0, true, "RUNNING", 1));
        doAnswer(call -> { reserved.addAndGet((int) call.getArgument(0) + (int) call.getArgument(1)); return null; })
                .when(guard).reserveModel(anyInt(), anyInt());
        server.enqueue(Script.tools(true, progress("budget-a")));
        assertThatThrownBy(() -> prepare(ignored -> { }, ignored -> { }))
                .isInstanceOfSatisfying(BusinessException.class, failure ->
                        assertThat(failure.getCode()).isEqualTo("ASSISTANT_BUDGET_EXCEEDED"));
        assertThat(server.requests).hasSize(1);
        verify(guard, times(1)).reserveModel(anyInt(), anyInt());
        assertThat(reserved.get()).isGreaterThan(0);
    }

    @Test void choosesTwoToolsUsesNonEmptyOwnProgressAndStreamsValidatedSegmentsBeforeFinal() throws Exception {
        server.enqueue(Script.tools(true, new ToolCall("a", "get_course_outline", "{\"courseId\":1}"), progress("b")));
        var visible = new CountDownLatch(1);
        Script finalScript = Script.answer(List.of(
                "{\"insufficientEvidence\":false,\"answerSegments\":[" + suggestion() + ",",
                fact() + "]}"), true, visible);
        server.enqueue(finalScript);
        List<String> deltas = new ArrayList<>();
        List<LangChainChatGenerator.ToolStatus> statuses = new ArrayList<>();

        var answer = prepare(text -> { deltas.add(text); visible.countDown(); }, statuses::add);

        assertThat(server.requests).hasSize(2);
        for (String body : server.requests) {
            assertThat(mapper.readTree(body).path("response_format").path("type").asText()).isEqualTo("json_object");
        }
        assertThat(finalScript.firstVisibleBeforeFinish.get()).isTrue();
        assertThat(String.join("", deltas)).isEqualTo(answer.text())
                .isEqualTo("已学习30秒，建议继续互斥锁重建。 [E2][E3]\n\n互斥锁限制并发重建。 [E1]");
        assertThat(deltas).allSatisfy(delta -> assertThat(delta).doesNotContain("answerSegments", "evidenceIds", "insufficientEvidence"));
        assertThat(statuses).extracting(LangChainChatGenerator.ToolStatus::type)
                .containsExactly("tool_start", "tool_end", "tool_start", "tool_end");
        assertThat(answer.citations()).extracting(EvidenceBook.Citation::kind).containsExactly("OUTLINE", "PROGRESS", "MATERIAL");
        verify(guard, times(2)).recordUsage(10, 5, 15);
        verify(guard, times(2)).reserveModel(anyInt(), eq(512));
        verify(executions, times(2)).complete(eq(7L), eq(stored), anyString(), anyLong());
        assertThat(mapper.readTree(answer.usageJson()).path("actual").path("totalTokens").asInt()).isEqualTo(30);
        var replay = new EvidenceBook(7L, 1, store, courses, mapper);
        codec.restoreInto(7, 1, answer.sourcesJson(), replay);
        assertThat(replay.references()).extracting(reference -> reference.get("kind"))
                .containsExactly("MATERIAL", "OUTLINE", "PROGRESS");
        String request = server.requests.get(1);
        assertThat(request).contains("tool_call_id", "positionSeconds", "30", "互斥锁重建", "get_my_learning_progress");
        assertThat(request).doesNotContain("900", "local_mock_only");
    }

    @Test void wireToolSchemaPreservesIntegerBoundsDefaultAndRequiredFields() throws Exception {
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":false,\"answerSegments\":[" + fact() + "]}"), true, null));
        prepare(ignored -> { }, ignored -> { });
        var request = mapper.readTree(server.requests.get(0));
        assertThat(request.path("tools")).hasSize(4);
        int checked = 0;
        for (var tool : request.path("tools")) {
            var parameters = tool.path("function").path("parameters");
            // DeepSeek 非 strictTools 模式下 SDK 不发送 additionalProperties，服务端仍拒绝额外字段。
            assertThat(parameters.path("required").isArray()).isTrue();
            var limit = parameters.path("properties").path("limit");
            if (!limit.isMissingNode()) {
                assertThat(limit.path("type").asText()).isEqualTo("integer");
                assertThat(limit.path("minimum").asInt()).isEqualTo(1);
                assertThat(limit.path("maximum").asInt()).isEqualTo(5);
                assertThat(limit.path("default").asInt()).isEqualTo(3);
                checked++;
            }
        }
        assertThat(checked).isEqualTo(2);
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void invalidLimitIsRejectedBeforeExecutionAndLogsOnlySafeDiagnostics(
            CapturedOutput output) {
        String privateQuery = "PRIVATE_TOOL_QUERY_NEVER_LOG";
        String arguments = "{\"courseId\":\"1\",\"query\":\"" + privateQuery + "\",\"limit\":10}";
        server.enqueue(Script.tools(true, new ToolCall("bad", "search_course_materials", arguments)));
        assertThatThrownBy(() -> prepare(ignored -> fail("不能显示草稿"), ignored -> fail("不能执行工具")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未通过校验");
        verifyNoInteractions(executions);
        assertThat(server.requests).hasSize(1);
        assertThat(output.getAll()).contains("requestId=tool-run", "tool=search_course_materials",
                "field=limit", "reason=OUT_OF_RANGE").doesNotContain(privateQuery, arguments);
    }

    @ParameterizedTest @ValueSource(strings = {"missing", "extra", "scope", "unknown", "malformed", "type"})
    @ExtendWith(OutputCaptureExtension.class)
    void rejectionDiagnosticsNeverEchoModelSuppliedNamesOrValues(String invalidCase,
            CapturedOutput output) {
        String name = "unknown".equals(invalidCase) ? "PRIVATE_UNKNOWN_TOOL" : "search_course_materials";
        String arguments = switch (invalidCase) {
            case "missing" -> "{\"courseId\":\"1\"}";
            case "extra" -> "{\"courseId\":\"1\",\"query\":\"PRIVATE_QUERY\",\"PRIVATE_FIELD\":true}";
            case "scope" -> "{\"courseId\":\"999\",\"query\":\"PRIVATE_QUERY\"}";
            case "malformed" -> "{\"query\":\"PRIVATE_QUERY\"";
            case "type" -> "{\"courseId\":\"1\",\"query\":\"PRIVATE_QUERY\",\"limit\":\"3\"}";
            default -> "{}";
        };
        String field = switch (invalidCase) {
            case "missing" -> "query";
            case "scope" -> "courseId";
            case "unknown" -> "tool";
            case "type" -> "limit";
            default -> "arguments";
        };
        String reason = switch (invalidCase) {
            case "missing" -> "MISSING_FIELD";
            case "extra" -> "UNEXPECTED_FIELD";
            case "scope" -> "SCOPE_MISMATCH";
            case "unknown" -> "UNKNOWN_TOOL";
            case "type" -> "EXPECTED_INTEGER";
            default -> "INVALID_JSON";
        };
        server.enqueue(Script.tools(true, new ToolCall("bad", name, arguments)));
        assertThatThrownBy(() -> prepare(ignored -> fail("不得显示草稿"), ignored -> fail("不得执行工具")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未通过校验");
        verifyNoInteractions(executions);
        assertThat(server.requests).hasSize(1);
        assertThat(output.getAll()).contains("requestId=tool-run", "field=" + field, "reason=" + reason)
                .doesNotContain("PRIVATE_QUERY", "PRIVATE_FIELD", "PRIVATE_UNKNOWN_TOOL");
        if ("unknown".equals(invalidCase)) assertThat(output.getAll()).contains("tool=UNKNOWN");
    }

    @Test void modelMayFinishWithoutUsingAnyTool() throws Exception {
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":false,\"answerSegments\":[" + fact() + "]}"), true, null));
        List<String> deltas = new ArrayList<>();
        var answer = prepare(deltas::add, ignored -> fail("不得假报工具执行"));
        assertThat(answer.text()).isEqualTo("互斥锁限制并发重建。 [E1]");
        assertThat(server.requests).hasSize(1);
        assertThat(mapper.readTree(server.requests.get(0)).path("response_format").path("type").asText())
                .isEqualTo("json_object");
        verifyNoInteractions(executions);
        verify(guard).recordUsage(10, 5, 15);
    }

    @Test void preferencesAreSentAsNonCitableDataAndPersistedAsDependencies() throws Exception {
        var preference = new com.learnhub.assistant.memory.PreferenceSnapshot(
                UUID.randomUUID().toString(), 1, "STYLE", "喜欢先看例子");
        var source = mock(com.learnhub.assistant.memory.PreferenceMemorySource.class);
        when(source.context(7, stored.message(), 3)).thenReturn(List.of(preference));
        when(source.current(7, preference.id())).thenReturn(preference);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("preferences", source);
        org.springframework.test.util.ReflectionTestUtils.setField(generator, "preferenceSources",
                beans.getBeanProvider(com.learnhub.assistant.memory.PreferenceMemorySource.class));
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":false,\"answerSegments\":[" + fact() + "]}"), true, null));
        var answer = prepare(ignored -> { }, ignored -> { });
        assertThat(server.requests.get(0)).contains("learningPreferences", "喜欢先看例子");
        assertThat(answer.citations()).hasSize(1).allSatisfy(citation -> assertThat(citation.kind()).isEqualTo("MATERIAL"));
        assertThat(mapper.readTree(answer.sourcesJson()).path("preferences")).hasSize(1);
        verify(source, atLeast(3)).current(7, preference.id());
    }

    @Test void changedPreferenceStopsBeforeReservationOrHttp() {
        var source = mock(com.learnhub.assistant.memory.PreferenceMemorySource.class);
        var id = UUID.randomUUID().toString();
        when(source.context(7, stored.message(), 3)).thenReturn(List.of(
                new com.learnhub.assistant.memory.PreferenceSnapshot(id, 1, "STYLE", "先看例子")));
        when(source.current(7, id)).thenReturn(
                new com.learnhub.assistant.memory.PreferenceSnapshot(id, 2, "STYLE", "先看原理"));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("preferences", source);
        org.springframework.test.util.ReflectionTestUtils.setField(generator, "preferenceSources",
                beans.getBeanProvider(com.learnhub.assistant.memory.PreferenceMemorySource.class));
        assertThatThrownBy(() -> prepare(ignored -> { }, ignored -> { })).isInstanceOf(BusinessException.class);
        assertThat(server.requests).isEmpty();
        verify(guard, never()).reserveModel(anyInt(), anyInt());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void optionalExtractionIsIncludedInFinalUsageWithoutErasingUnknownCost(boolean known) throws Exception {
        var extraction = mock(com.learnhub.assistant.memory.PreferenceExtractionService.class);
        when(extraction.extract(eq(7L), eq(stored), eq(guard), any(), any(), any())).thenReturn(
                new com.learnhub.assistant.memory.PreferenceExtractionService.Result(true, known, known ? 2 : 0, known ? 1 : 0, known ? 3 : 0));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("extraction", extraction);
        org.springframework.test.util.ReflectionTestUtils.setField(generator, "extractionServices",
                beans.getBeanProvider(com.learnhub.assistant.memory.PreferenceExtractionService.class));
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":false,\"answerSegments\":[" + fact() + "]}"), true, null));
        var answer = prepare(ignored -> { }, ignored -> { });
        var usage = mapper.readTree(answer.usageJson());
        assertThat(usage.path("phase").asText()).isEqualTo(known ? "ACTUAL" : "PARTIAL");
        assertThat(usage.path("actual").path("totalTokens").asInt()).isEqualTo(known ? 18 : 15);
        assertThat(answer.text()).isEqualTo("互斥锁限制并发重建。 [E1]");
        assertThat(server.requests).hasSize(1);
        verify(extraction).extract(eq(7L), eq(stored), eq(guard), any(), any(), any());
    }

    @ParameterizedTest @ValueSource(strings = {"duplicateId", "malformedSecondArguments"})
    void validatesEntireToolBatchBeforeAnyToolExecutes(String invalidCase) {
        ToolCall second = "duplicateId".equals(invalidCase) ? progress("a")
                : new ToolCall("b", "get_my_learning_progress", "{\"courseId\":\"1\",\"unexpected\":true}");
        server.enqueue(Script.tools(true, outline("a"), second));
        assertThatThrownBy(() -> prepare(ignored -> fail("不得显示无效工具输出"), ignored -> fail("不得开始工具")))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(executions);
        verify(chapters, never()).selectList(any());
        verify(progress, never()).getByCourse(anyLong(), anyLong());
        assertThat(server.requests).hasSize(1);
    }

    @Test void secondModelBudgetFailureAllowsOnlyTheFirstNetworkRequest() {
        server.enqueue(Script.tools(true, outline("a")));
        AtomicInteger reservations = new AtomicInteger();
        doAnswer(ignored -> {
            if (reservations.incrementAndGet() == 2)
                throw new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "第二轮预算耗尽");
            return null;
        }).when(guard).reserveModel(anyInt(), anyInt());
        assertThatThrownBy(() -> prepare(ignored -> { }, ignored -> { }))
                .isInstanceOf(BusinessException.class).hasMessageContaining("预算");
        assertThat(server.requests).hasSize(1);
        verify(executions).complete(eq(7L), eq(stored), anyString(), anyLong());
        verify(guard).recordUsage(10, 5, 15);
    }

    @Test void businessToolFailureStopsBeforeAnotherModelRequestAndMarksToolFailed() {
        server.enqueue(Script.tools(true, outline("a")));
        when(chapters.selectList(any())).thenThrow(new DataAccessResourceFailureException("private_database_sentinel"));
        List<LangChainChatGenerator.ToolStatus> statuses = new ArrayList<>();
        assertThatThrownBy(() -> prepare(ignored -> { }, statuses::add))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining("private_database_sentinel");
        assertThat(server.requests).hasSize(1);
        assertThat(statuses).extracting(status -> status.payload().get("status")).containsExactly("RUNNING", "FAILED");
        verify(executions).fail(eq(7L), eq(stored), anyString(), anyLong(), anyString(), eq(false));
        verify(executions, never()).complete(anyLong(), any(), anyString(), anyLong());
    }

    @Test void missingUsagePreventsEveryToolExecution() {
        server.enqueue(Script.tools(false, outline("a")));
        assertThatThrownBy(() -> prepare(ignored -> { }, ignored -> fail("缺用量不得执行工具")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("usage");
        verifyNoInteractions(executions);
        verify(guard, never()).recordUsage(anyLong(), anyLong(), anyLong());
        assertThat(server.requests).hasSize(1);
    }

    @Test void cancellationAfterFirstValidatedSegmentFailsAndSuppressesLaterSegments() {
        var visible = new CountDownLatch(1);
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":false,\"answerSegments\":[" + fact() + ",",
                fact() + "]}"), true, visible));
        List<String> deltas = new ArrayList<>();
        try (var io = new AssistantIoScope(Duration.ofSeconds(8))) {
            io.attach();
            assertThatThrownBy(() -> generator.prepare(7, stored, guard, delta -> {
                deltas.add(delta); io.cancel(); visible.countDown();
            }, ignored -> { })).isInstanceOf(BusinessException.class).hasMessageContaining("取消");
        }
        assertThat(deltas).containsExactly("互斥锁限制并发重建。 [E1]");
        verify(persistence, never()).complete(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private LangChainChatGenerator.PreparedAnswer prepare(Consumer<String> delta,
            Consumer<LangChainChatGenerator.ToolStatus> status) {
        try (var io = new AssistantIoScope(Duration.ofSeconds(8))) {
            io.attach();
            return generator.prepare(7, stored, guard, delta, status);
        }
    }

    private static ToolCall outline(String id) { return new ToolCall(id, "get_course_outline", "{\"courseId\":\"1\"}"); }
    private static ToolCall progress(String id) { return new ToolCall(id, "get_my_learning_progress", "{\"courseId\":\"1\"}"); }
    private static String fact() { return "{\"text\":\"互斥锁限制并发重建。\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}"; }
    private static String suggestion() { return "{\"text\":\"已学习30秒，建议继续互斥锁重建。\",\"evidenceIds\":[\"E2\",\"E3\"],\"kind\":\"SUGGESTION\"}"; }

    private static final class Script {
        final List<ToolCall> calls;
        final List<String> deltas;
        final boolean usage;
        final CountDownLatch firstVisible;
        final AtomicBoolean firstVisibleBeforeFinish = new AtomicBoolean();
        Script(List<ToolCall> calls, List<String> deltas, boolean usage, CountDownLatch firstVisible) {
            this.calls = calls; this.deltas = deltas; this.usage = usage; this.firstVisible = firstVisible;
        }
        static Script tools(boolean usage, ToolCall... calls) { return new Script(List.of(calls), List.of(), usage, null); }
        static Script answer(List<String> deltas, boolean usage, CountDownLatch visible) { return new Script(List.of(), deltas, usage, visible); }
    }

    private final class MockEndpoint implements AutoCloseable {
        private final HttpServer http;
        private final ExecutorService handlers = Executors.newFixedThreadPool(2);
        private final ArrayDeque<Script> scripts = new ArrayDeque<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        MockEndpoint() throws IOException {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/v1/chat/completions", this::handle);
            http.setExecutor(handlers); http.start();
        }
        synchronized void enqueue(Script script) { scripts.add(script); }
        URI endpoint() { return URI.create("http://127.0.0.1:" + http.getAddress().getPort() + "/v1"); }
        private void handle(HttpExchange exchange) throws IOException {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Script script;
            synchronized (this) { script = scripts.poll(); }
            if (script == null) { exchange.sendResponseHeaders(500, -1); exchange.close(); return; }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                if (!script.calls.isEmpty()) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    for (int i = 0; i < script.calls.size(); i++) {
                        ToolCall call = script.calls.get(i);
                        calls.add(Map.of("index", i, "id", call.id(), "type", "function", "function",
                                Map.of("name", call.name(), "arguments", call.arguments())));
                    }
                    write(output, chunk(Map.of("role", "assistant", "tool_calls", calls), null));
                } else {
                    for (int i = 0; i < script.deltas.size(); i++) {
                        write(output, chunk(Map.of("content", script.deltas.get(i)), null));
                        if (i == 0 && script.firstVisible != null) {
                            try { script.firstVisibleBeforeFinish.set(script.firstVisible.await(3, TimeUnit.SECONDS)); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                }
                write(output, chunk(Map.of(), script.calls.isEmpty() ? "stop" : "tool_calls"));
                if (script.usage) write(output, mapper.writeValueAsString(Map.of("id", "mock", "object", "chat.completion.chunk",
                        "created", 1, "model", "deepseek-flash", "choices", List.of(), "usage",
                        Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15))));
                output.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8)); output.flush();
            } catch (IOException disconnected) { /* 取消测试主动断开连接。 */ }
        }
        private String chunk(Map<String, Object> delta, String finish) throws IOException {
            var choice = mapper.createObjectNode().put("index", 0);
            choice.set("delta", mapper.valueToTree(delta));
            if (finish == null) choice.putNull("finish_reason"); else choice.put("finish_reason", finish);
            return mapper.writeValueAsString(Map.of("id", "mock", "object", "chat.completion.chunk", "created", 1,
                    "model", "deepseek-flash", "choices", List.of(choice)));
        }
        private void write(OutputStream output, String json) throws IOException {
            output.write(("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
        }
        @Override public void close() throws InterruptedException {
            http.stop(0); handlers.shutdownNow(); assertThat(handlers.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
