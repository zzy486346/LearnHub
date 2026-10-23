package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatEvidenceCodec;
import com.learnhub.assistant.chat.application.ChatHistoryService;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.chat.application.ContextCheckpoint;
import com.learnhub.assistant.chat.application.ChatToolExecutionStore;
import com.learnhub.assistant.chat.application.ChatToolResultStore;
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
    private final ChatToolResultStore results = mock(ChatToolResultStore.class);
    private final Map<String, ChatToolResultStore.Stored> savedResults = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<String> lastResultRef = new java.util.concurrent.atomic.AtomicReference<>();
    private final AtomicBoolean revokeOnRead = new AtomicBoolean();
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
        limits.setModelWindowTokens(24000); // 本地 HTTP Mock 的显式窗口。
        server = new MockEndpoint();
        when(guard.remaining()).thenReturn(Duration.ofSeconds(8));
        when(guard.requestId()).thenReturn(stored.id());
        when(guard.summary()).thenReturn(new AssistantRunSummary(stored.id(), 2, 1, 2,
                10_000, 20, 10, 30, true, "RUNNING", 1));
        when(persistence.conversation(7, stored.conversationId())).thenReturn(
                new ChatPersistenceService.Conversation(stored.conversationId(), 7, 1, "课程", 1));
        when(persistence.messages(7, stored.conversationId(), Long.MAX_VALUE, 48)).thenReturn(List.of());
        when(persistence.contextEvents(eq(7L), eq(stored.conversationId()), anyLong(), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(2); int limit = call.getArgument(3);
            var rows = persistence.messages(7, stored.conversationId(), Long.MAX_VALUE, 48);
            return rows.stream().filter(row -> row.seq() > after).limit(limit).map(row -> {
                var user = rows.stream().filter(item -> item.runId().equals(row.runId()) && "USER".equals(item.role())).findFirst().orElse(row);
                var assistant = rows.stream().filter(item -> item.runId().equals(row.runId()) && "ASSISTANT".equals(item.role())).findFirst().orElse(null);
                return new ContextCheckpoint.Event(row, assistant == null ? "CANCELLED" : assistant.status(), user.id(), assistant == null ? null : assistant.id());
            }).toList();
        });
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
        generator.setToolResultStore(results);
        doAnswer(call -> {
            String ref = call.getArgument(5); String full = call.getArgument(6); String dependencies = call.getArgument(8);
            savedResults.put(ref, new ChatToolResultStore.Stored(ref, call.getArgument(4), full, dependencies,
                    "a".repeat(64), full.getBytes(StandardCharsets.UTF_8).length));
            lastResultRef.set(ref); return null;
        }).when(results).completeWithResult(eq(7L), eq(1L), eq(stored), anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyLong());
        when(results.read(eq(7L), eq(1L), eq(stored), anyString())).thenAnswer(call -> {
            if (revokeOnRead.get()) when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of());
            var result = savedResults.get((String) call.getArgument(3));
            if (result == null) throw new BusinessException("ASSISTANT_TOOL_RESULT_UNAVAILABLE", "结果不可用");
            return result;
        });
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

    @Test void missingVerifiedWindowRejectsBeforeReservationAndHttp() {
        limits.setModelWindowTokens(0);
        assertThatThrownBy(() -> prepare(ignored -> { }, ignored -> { }))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo("AI_MODEL_CONTEXT_WINDOW_UNCONFIGURED"));
        assertThat(server.requests).isEmpty();
        verify(guard, never()).reserveModel(anyInt(), anyInt());
    }

    @Test void secondRoundHttpBurstSurvivesSlowCheckpointsAndAccountsBothResponses() {
        server.enqueue(Script.tools(true, outline("burst-a"), progress("burst-b")));
        String body = "互斥锁限制并发重建。".repeat(20);
        String raw = "{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"" + body
                + "\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}]}";
        Script burst = Script.answer(raw.codePoints().mapToObj(cp -> new String(Character.toChars(cp))).toList(), true, null);
        server.enqueue(burst);
        var waited = new AtomicBoolean();
        doAnswer(ignored -> {
            if (server.requests.size() == 2 && waited.compareAndSet(false, true)) {
                assertThat(burst.finished.await(3, TimeUnit.SECONDS)).isTrue();
                // 模拟数据库/Redis复核较慢，允许读取线程先积累小分片。
                Thread.sleep(150);
            }
            return null;
        }).when(guard).check();
        List<String> deltas = new ArrayList<>();
        var answer = prepare(deltas::add, ignored -> { });
        assertThat(waited).isTrue();
        assertThat(answer.text()).isEqualTo(body + " [E1]").isEqualTo(String.join("", deltas));
        assertThat(server.requests).hasSize(2);
        verify(guard, times(2)).recordUsage(10, 5, 15);
        assertThat(answer.usageJson()).contains("MODEL_ANSWER_WITH_VALID_REFERENCES");
    }

    @Test void semanticSummarySurvivesToolLoopAndKeepsRecentHistoryAndAuthorizedEvidence() throws Exception {
        semanticToolHistory();
        server.enqueue(Script.summary(semanticSummary()));
        server.enqueue(Script.tools(true, progress("summary-progress"), outline("summary-outline")));
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":false,\"answerSegments\":[" + fact()
                + ", {\"text\":\"已学习30秒，建议继续。\",\"evidenceIds\":[\"E2\",\"E3\"],\"kind\":\"SUGGESTION\"}]}"), true, null));

        var answer = prepare(ignored -> { }, ignored -> { });

        assertThat(server.requests).hasSize(3);
        assertThat(server.requests.get(0)).contains("OLD_PREFIX_MARKER");
        var wireRequests = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        for (String request : server.requests) wireRequests.add(mapper.readTree(request));
        assertThat(wireRequests).filteredOn(request -> !request.path("stream").asBoolean()).hasSize(1);
        assertThat(wireRequests.subList(1, 3)).allSatisfy(request ->
                assertThat(request.path("stream").asBoolean()).isTrue());
        for (String request : server.requests.subList(1, 3)) {
            var current = currentUserJson(request);
            assertThat(current.path("taskState").path("corrections").get(0).path("value").asText())
                    .isEqualTo("否定互斥锁");
            assertThat(current.path("semanticContext").path("schemaVersion").asInt()).isEqualTo(1);
            assertThat(request).contains("近期问题一", "近期回答二").doesNotContain("OLD_PREFIX_MARKER");
        }
        var finalMessages = mapper.readTree(server.requests.get(2)).path("messages");
        var toolCallIds = new ArrayList<String>();
        var toolResultIds = new ArrayList<String>();
        finalMessages.forEach(message -> {
            message.path("tool_calls").forEach(call -> toolCallIds.add(call.path("id").asText()));
            if ("tool".equals(message.path("role").asText())) toolResultIds.add(message.path("tool_call_id").asText());
        });
        assertThat(toolCallIds).containsExactly("summary-progress", "summary-outline");
        assertThat(toolResultIds).containsExactlyElementsOf(toolCallIds);
        assertThat(mapper.readTree(answer.usageJson()).path("actual").path("totalTokens").asInt()).isEqualTo(45);
        assertThat(answer.usageJson()).contains("SUMMARY_READY", "\"summaryCalls\":1");
        var replay = new EvidenceBook(7L, 1, store, courses, mapper);
        codec.restoreInto(7, 1, answer.sourcesJson(), replay);
        assertThat(replay.references()).extracting(reference -> reference.get("kind"))
                .containsExactly("MATERIAL", "PROGRESS", "OUTLINE");
        verify(guard).tryReserveSummary(anyInt(), eq(512), anyInt(), eq(512));
        verify(guard, times(2)).reserveModel(anyInt(), eq(512));
        verify(guard, times(3)).recordUsage(10, 5, 15);
    }

    @Test void largeOutlineUsesProjectionAndAuthorizedReadbackWithoutReinsertingFullBookIntoCurrentQuestion() throws Exception {
        largeProjectedOutline();
        server.enqueue(Script.tools(true, outline("project-outline")));
        server.enqueue(Script.tools(true, new ToolCall("read-page", "read_tool_result",
                "{\"resultRef\":\"$RESULT_REF\",\"page\":3,\"fields\":\"DETAILS\"}")));
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\"目录信息。\",\"evidenceIds\":[\"E2\"],\"kind\":\"FACT\"}]}"), true, null));
        var answer = prepare(ignored -> { }, ignored -> { });
        assertThat(server.requests).hasSize(3);
        assertThat(server.requests.get(1)).doesNotContain("LATE_OUTLINE_MARKER");
        assertThat(server.requests.get(2)).contains("LATE_OUTLINE_MARKER");
        for (String request : server.requests) assertThat(currentUserJson(request).toString()).doesNotContain("LATE_OUTLINE_MARKER");
        assertThat(savedResults.values()).hasSize(1).allSatisfy(result -> assertThat(result.resultJson()).contains("LATE_OUTLINE_MARKER"));
        assertThat(answer.sourcesJson()).contains("LATE_OUTLINE_MARKER"); // 正式依赖仍保留全量快照。
        verify(guard).toolCall(startsWith("read_tool_result:"));
        verify(results).read(7, 1, stored, lastResultRef.get());
        verify(executions, times(2)).begin(eq(7L), eq(stored), anyInt(), any(ToolCall.class), anyString());
        var finalMessages = mapper.readTree(server.requests.get(2)).path("messages");
        var calls = new ArrayList<String>(); var replies = new ArrayList<String>();
        finalMessages.forEach(message -> {
            message.path("tool_calls").forEach(call -> calls.add(call.path("id").asText()));
            if ("tool".equals(message.path("role").asText())) replies.add(message.path("tool_call_id").asText());
        });
        assertThat(calls).containsExactly("project-outline", "read-page");
        assertThat(replies).containsExactlyElementsOf(calls);
    }

    @Test void revokedObservationCannotSendAReadbackPageToTheNextModelRound() {
        largeProjectedOutline(); revokeOnRead.set(true);
        server.enqueue(Script.tools(true, outline("project-outline")));
        server.enqueue(Script.tools(true, new ToolCall("read-page", "read_tool_result",
                "{\"resultRef\":\"$RESULT_REF\",\"page\":3,\"fields\":\"DETAILS\"}")));
        assertThatThrownBy(() -> prepare(ignored -> { }, ignored -> { }))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getCode()).isEqualTo("ASSISTANT_EVIDENCE_CHANGED"));
        assertThat(server.requests).hasSize(2).allSatisfy(body -> assertThat(body).doesNotContain("LATE_OUTLINE_MARKER"));
        verify(guard).toolCall(startsWith("read_tool_result:"));
    }

    private void largeProjectedOutline() {
        limits.setContextRedesignEnabled(true); limits.setMaxRunTokens(100000);
        limits.setMaxInputBytes(64000); limits.setModelWindowTokens(128000);
        var rows = new ArrayList<CourseLesson>();
        var ids = new ArrayList<Long>();
        for (int index = 0; index < 24; index++) {
            CourseLesson row = new CourseLesson(); row.setId(2L + index); row.setChapterId(3L);
            row.setTitle(index == 20 ? "LATE_OUTLINE_MARKER" : "课时" + index);
            row.setSortOrder(index); row.setDurationSeconds(300); row.setFreePreview(true);
            rows.add(row); ids.add(row.getId());
        }
        when(lessons.selectList(any())).thenReturn(rows);
        when(courses.readableLessonIds(7L, 1L)).thenReturn(ids);
    }

    @Test void nonemptyWeakEvidenceRefusalPublishesNoSourcesAndRetainsRevalidationEnvelope() {
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":true,\"answerSegments\":[]}"), true, null));
        var answer = prepare(ignored -> { }, ignored -> { });
        assertThat(answer.text()).isEqualTo(LangChainChatGenerator.INSUFFICIENT);
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.usageJson()).contains("MODEL_REPORTED_INSUFFICIENT");
        var service = new ChatHistoryService(persistence, courses, qa, mapper, codec);
        var historyMessage = new ChatPersistenceService.Message("answer", 2, "ASSISTANT", answer.text(),
                "COMPLETED", stored.id(), answer.sourcesJson());
        assertThat(service.verify(7, 1, historyMessage).sources()).isEmpty();
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of());
        assertThat(service.verify(7, 1, historyMessage).restricted()).isTrue();
        assertThat(server.requests).hasSize(1);
    }

    @Test void validReferencesAreExplicitlyNotReportedAsSemanticallyVerifiedAnswers() {
        // 负向质量探针：故意返回与真实 E1 无关的细节。结构校验不等于语义支持校验。
        String unsupported = "Lua执行失败会自动回滚MySQL事务。";
        server.enqueue(Script.answer(List.of("{\"insufficientEvidence\":false,\"answerSegments\":[{\"text\":\""
                + unsupported + "\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}]}"), true, null));
        List<String> deltas = new ArrayList<>();
        var answer = prepare(deltas::add, ignored -> { });
        // 不把该结果计为回答正确率通过。枚举只证明本轮引用存在，不能命名为 SUPPORTED。
        assertThat(answer.usageJson()).contains("MODEL_ANSWER_WITH_VALID_REFERENCES").doesNotContain("SEMANTICALLY_VERIFIED");
        assertThat(source.text()).doesNotContain(unsupported);
        assertThat(String.join("", deltas)).isEqualTo(answer.text());
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

    private void semanticToolHistory() throws Exception {
        limits.setContextRedesignEnabled(true);
        limits.setMaxInputBytes(32_000);
        limits.setModelWindowTokens(64_000);
        limits.setMaxRunTokens(100_000); // 仅本地 Mock fixture；不改变生产默认额度。
        limits.setContextSoftPressure(0.15);
        limits.setContextTargetPressure(0.1);
        String dependencies = mapper.writeValueAsString(List.of(source));
        String oldPrefix = "OLD_PREFIX_MARKER ".repeat(300);
        when(persistence.messages(7, stored.conversationId(), Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", oldPrefix + "比较互斥锁和逻辑过期", "old-1", "[]"),
                message(2, "ASSISTANT", "先比较两种方案。[E1]", "old-1", dependencies),
                message(3, "USER", "否定互斥锁，选择逻辑过期，每天30分钟。", "old-2", "[]"),
                message(4, "ASSISTANT", "下一步先做练习。[E1]", "old-2", dependencies),
                message(5, "USER", "近期问题一：进行第二步练习", "recent-1", "[]"),
                message(6, "ASSISTANT", "近期回答一。[E1]", "recent-1", dependencies),
                message(7, "USER", "近期问题二：继续查进度", "recent-2", "[]"),
                message(8, "ASSISTANT", "近期回答二。[E1]", "recent-2", dependencies)));
        var modelCalls = new AtomicInteger();
        var recordedCalls = new AtomicInteger();
        var reserved = new java.util.concurrent.atomic.AtomicLong();
        var actual = new java.util.concurrent.atomic.AtomicLong();
        var prepaidInput = new AtomicInteger();
        var prepaidOutput = new AtomicInteger();
        when(guard.tryReserveSummary(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            modelCalls.incrementAndGet();
            reserved.addAndGet((int) call.getArgument(0) + (int) call.getArgument(1)
                    + (int) call.getArgument(2) + (int) call.getArgument(3));
            prepaidInput.set(call.getArgument(2));
            prepaidOutput.set(call.getArgument(3));
            return true;
        });
        doAnswer(call -> {
            modelCalls.incrementAndGet();
            int input = call.getArgument(0);
            int output = call.getArgument(1);
            if (prepaidOutput.get() > 0) {
                assertThat(input).isLessThanOrEqualTo(prepaidInput.get());
                assertThat(output).isLessThanOrEqualTo(prepaidOutput.get());
                prepaidInput.set(0);
                prepaidOutput.set(0);
            } else {
                reserved.addAndGet(input + output);
            }
            return null;
        }).when(guard).reserveModel(anyInt(), anyInt());
        doAnswer(call -> {
            recordedCalls.incrementAndGet();
            actual.addAndGet((long) call.getArgument(2));
            return null;
        }).when(guard).recordUsage(anyLong(), anyLong(), anyLong());
        when(guard.summary()).thenAnswer(ignored -> new AssistantRunSummary(stored.id(), modelCalls.get(), 0, 0,
                reserved.get(), 0, 0, actual.get(), recordedCalls.get() == modelCalls.get() && prepaidOutput.get() == 0,
                "RUNNING", 1, prepaidInput.get(), prepaidOutput.get()));
    }

    private ChatPersistenceService.Message message(long seq, String role, String content, String runId, String sources) {
        return new ChatPersistenceService.Message("history-" + seq, seq, role, content, "COMPLETED", runId, sources);
    }

    private String semanticSummary() throws Exception {
        Map<String, Object> item = Map.of("value", "否定互斥锁", "sourceMessageSeqs", List.of(3),
                "dependencyKeys", List.of(), "inferred", false);
        Map<String, Object> task = new java.util.LinkedHashMap<>();
        for (String field : List.of("goal", "topic", "constraints", "decisions", "corrections", "openQuestions", "nextStep"))
            task.put(field, List.of());
        task.put("corrections", List.of(item));
        return mapper.writeValueAsString(Map.of("schemaVersion", 1, "taskState", task,
                "conversationSummary", List.of(), "unresolvedReferences", List.of()));
    }

    private com.fasterxml.jackson.databind.JsonNode currentUserJson(String request) throws Exception {
        var messages = mapper.readTree(request).path("messages");
        for (int index = messages.size() - 1; index >= 0; index--) {
            var message = messages.get(index);
            if ("user".equals(message.path("role").asText())) return mapper.readTree(message.path("content").asText());
        }
        fail("请求缺少当前用户消息");
        return mapper.nullNode();
    }

    private static ToolCall outline(String id) { return new ToolCall(id, "get_course_outline", "{\"courseId\":\"1\"}"); }
    private static ToolCall progress(String id) { return new ToolCall(id, "get_my_learning_progress", "{\"courseId\":\"1\"}"); }
    private static String fact() { return "{\"text\":\"互斥锁限制并发重建。\",\"evidenceIds\":[\"E1\"],\"kind\":\"FACT\"}"; }
    private static String suggestion() { return "{\"text\":\"已学习30秒，建议继续互斥锁重建。\",\"evidenceIds\":[\"E2\",\"E3\"],\"kind\":\"SUGGESTION\"}"; }

    private static final class Script {
        final List<ToolCall> calls;
        final List<String> deltas;
        final boolean usage;
        final String summary;
        final CountDownLatch firstVisible;
        final AtomicBoolean firstVisibleBeforeFinish = new AtomicBoolean();
        final CountDownLatch finished = new CountDownLatch(1);
        Script(List<ToolCall> calls, List<String> deltas, boolean usage, String summary, CountDownLatch firstVisible) {
            this.calls = calls; this.deltas = deltas; this.usage = usage; this.summary = summary; this.firstVisible = firstVisible;
        }
        static Script tools(boolean usage, ToolCall... calls) { return new Script(List.of(calls), List.of(), usage, null, null); }
        static Script answer(List<String> deltas, boolean usage, CountDownLatch visible) {
            return new Script(List.of(), deltas, usage, null, visible);
        }
        static Script summary(String summary) { return new Script(List.of(), List.of(), true, summary, null); }
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
            if (script.summary != null) {
                byte[] response = mapper.writeValueAsBytes(Map.of("id", "summary-mock", "object", "chat.completion",
                        "created", 1, "model", "deepseek-flash", "choices", List.of(Map.of("index", 0,
                                "message", Map.of("role", "assistant", "content", script.summary), "finish_reason", "stop")),
                        "usage", Map.of("prompt_tokens", 10, "completion_tokens", 5, "total_tokens", 15)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) { output.write(response); }
                finally { script.finished.countDown(); }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                if (!script.calls.isEmpty()) {
                    List<Map<String, Object>> calls = new ArrayList<>();
                    for (int i = 0; i < script.calls.size(); i++) {
                        ToolCall call = script.calls.get(i);
                        String arguments = call.arguments();
                        if (arguments.contains("$RESULT_REF")) arguments = arguments.replace("$RESULT_REF", java.util.Objects.requireNonNull(lastResultRef.get()));
                        calls.add(Map.of("index", i, "id", call.id(), "type", "function", "function",
                                Map.of("name", call.name(), "arguments", arguments)));
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
            finally { script.finished.countDown(); }
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
