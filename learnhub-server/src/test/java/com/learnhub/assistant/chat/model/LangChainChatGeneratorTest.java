package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.ChatHistoryService;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.chat.application.ContextCheckpoint;
import com.learnhub.assistant.knowledge.CurrentKnowledgeChunk;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.assistant.runtime.AssistantLimits;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.assistant.runtime.AssistantRunSummary;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LangChainChatGeneratorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatPersistenceService persistence = mock(ChatPersistenceService.class);
    private final CourseService courses = mock(CourseService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final CourseQaService qa = mock(CourseQaService.class);
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final AssistantRunGuard.Run guard = mock(AssistantRunGuard.Run.class);
    private final AiProperties properties = new AiProperties();
    private final AssistantLimits limits = new AssistantLimits();
    private final KnowledgeRetrievalService.Source source = new KnowledgeRetrievalService.Source(
            "9", 1, "11", "1", "2", "缓存课程", "互斥重建", 0,
            "热点缓存失效时可以用互斥锁限制并发重建。", 1);
    private final ChatPersistenceService.Run stored = new ChatPersistenceService.Run(
            "run-current", "conversation", 7, "request", "RUNNING", "lease", "它有什么局限？");
    private LocalSseServer server;
    private LangChainChatGenerator generator;

    @Test void enabledLowPressureAddsNoSummaryCallAndKeepsCurrentQuestion() throws Exception {
        limits.setContextRedesignEnabled(true);
        server.enqueue(List.of("低压力回答。[E1]"), "stop", true, 0);
        var answer = prepare(ignored -> { });
        assertThat(server.requestCount()).isOne();
        assertThat(answer.usageJson()).contains("BELOW_PRESSURE", "\"summaryCalls\":0");
        assertThat(server.requests().get(0)).contains(stored.message(), source.text());
    }

    @Test void enabledPressureSummarizesContinuousOldPrefixAndKeepsCorrectionsAndRecentRounds() throws Exception {
        semanticHistory();
        server.enqueueSummary(semanticSummary(), true);
        server.enqueue(List.of("压缩后回答。[E1]"), "stop", true, 0);
        var answer = prepare(ignored -> { });
        assertThat(server.requestCount()).isEqualTo(2);
        assertThat(server.requests().get(0)).contains("OLD_PREFIX_MARKER");
        assertThat(server.requests().get(1)).doesNotContain("OLD_PREFIX_MARKER").contains("第二步练习", "近期回答");
        var messages = mapper.readTree(server.requests().get(1)).path("messages");
        var current = mapper.readTree(messages.get(messages.size() - 1).path("content").asText());
        assertThat(current.path("taskState").path("constraints").get(0).path("value").asText()).isEqualTo("每天30分钟");
        assertThat(current.path("taskState").path("corrections").get(0).path("sourceMessageSeqs").get(0).asLong()).isEqualTo(3);
        assertThat(messages.get(0).path("content").asText()).doesNotContain("每天30分钟");
        assertThat(answer.usageJson()).contains("SUMMARY_READY", "\"summaryCalls\":1", "\"coveredThroughSeq\":4");
        assertThat(mapper.readTree(answer.usageJson()).path("actual").path("totalTokens").asInt()).isEqualTo(10);
        verify(guard).tryReserveSummary(anyInt(), eq(512), anyInt(), eq(512));
        verify(guard).reserveModel(anyInt(), eq(512));
        verify(guard, org.mockito.Mockito.times(2)).recordUsage(3, 2, 5);
    }

    @Test void forgedSummaryNeverEntersAnswerRequestButItsUsageIsStillCharged() throws Exception {
        semanticHistory();
        server.enqueueSummary(semanticSummary().replace("每天30分钟", "FORGED_SUMMARY_SENTINEL").replace("[3]", "[999]"), true);
        server.enqueue(List.of("降级回答。[E1]"), "stop", true, 0);
        var answer = prepare(ignored -> { });
        assertThat(server.requestCount()).isEqualTo(2);
        assertThat(server.requests().get(1)).doesNotContain("FORGED_SUMMARY_SENTINEL");
        assertThat(answer.usageJson()).contains("SUMMARY_FAILED", "\"summaryCalls\":1");
        assertThat(mapper.readTree(answer.usageJson()).path("actual").path("totalTokens").asInt()).isEqualTo(10);
        verify(guard, org.mockito.Mockito.times(2)).recordUsage(3, 2, 5);
    }

    @Test void freshGeneratorRestoresValidatedCheckpointWithoutSendingCoveredRawTextAgain() throws Exception {
        semanticHistory();
        server.enqueueSummary(semanticSummary(), true);
        server.enqueue(List.of("首次回答。[E1]"), "stop", true, 0);
        var initial = prepare(ignored -> { });
        assertThat(initial.pendingCheckpoint()).isNotNull();
        publishMockCheckpoint(initial.pendingCheckpoint());
        generator.close();
        generator = new LangChainChatGenerator(persistence, new ChatHistoryService(persistence, courses, qa, mapper),
                retrieval, qa, mapper, properties, limits, server.endpoint());
        server.enqueue(List.of("恢复后回答。[E1]"), "stop", true, 0);
        var restored = prepare(ignored -> { });
        assertThat(server.requests()).hasSize(3);
        assertThat(server.requests().get(2)).contains("每天30分钟", "第二步练习").doesNotContain("OLD_PREFIX_MARKER");
        assertThat(restored.pendingCheckpoint()).isNull();
        assertThat(restored.usageJson()).contains("CHECKPOINT_RESTORED", "\"summaryCalls\":0", "\"coveredThroughSeq\":4");
    }

    @Test void rollingSummaryAcceptsOnlyReferencedOldProvenanceAndNewContinuousPrefix() throws Exception {
        semanticHistory();
        server.enqueueSummary(semanticSummary(), true);
        server.enqueue(List.of("首次回答。[E1]"), "stop", true, 0);
        var initial = prepare(ignored -> { });
        var checkpoint = publishMockCheckpoint(initial.pendingCheckpoint());
        limits.setMaxInputBytes(32000); properties.setMaxInputBytes(32000);
        limits.setModelWindowTokens(64000); limits.setContextSoftPressure(0.1);
        var expanded = new ArrayList<>(persistence.messages(7, "conversation", Long.MAX_VALUE, 48));
        String dependencies = mapper.writeValueAsString(List.of(source));
        expanded.add(message(9, "USER", "新近期问题一", "new-1", "[]"));
        expanded.add(message(10, "ASSISTANT", "新近期回答一。[E1]", "new-1", dependencies));
        expanded.add(message(11, "USER", "新近期问题二", "new-2", "[]"));
        expanded.add(message(12, "ASSISTANT", "新近期回答二。[E1]", "new-2", dependencies));
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(expanded);
        server.enqueueSummary(semanticSummary(), true); // 保留旧 USER seq=3 的修正来源。
        server.enqueue(List.of("滚动回答。[E1]"), "stop", true, 0);
        var rolling = prepare(ignored -> { });
        assertThat(server.requests().get(2)).contains("previousSummary", "每天30分钟", "第二步练习").doesNotContain("OLD_PREFIX_MARKER");
        assertThat(rolling.pendingCheckpoint().expectedRevision()).isOne();
        assertThat(rolling.pendingCheckpoint().expectedHash()).isEqualTo(checkpoint.coveredInputHash());
        assertThat(rolling.pendingCheckpoint().baseThroughSeq()).isEqualTo(4);
        assertThat(rolling.pendingCheckpoint().coveredThroughSeq()).isEqualTo(8);
        assertThat(rolling.pendingCheckpoint().events()).extracting(event -> event.message().seq()).containsExactly(5L, 6L, 7L, 8L);
        assertThat(rolling.pendingCheckpoint().provenanceJson()).contains("\"seq\":3").doesNotContain("\"seq\":1");
        assertThat(server.requests().get(3)).contains("新近期回答一", "新近期回答二").doesNotContain("OLD_PREFIX_MARKER");
    }

    private ContextCheckpoint.Snapshot publishMockCheckpoint(ContextCheckpoint.Pending pending) {
        assertThat(pending).isNotNull();
        var checkpoint = new ContextCheckpoint.Snapshot(1, pending.floorSeq(), pending.coveredFromSeq(), pending.coveredThroughSeq(),
                ContextCheckpoint.inputHash("conversation", pending.floorSeq(), ContextCheckpoint.GENESIS, pending.events()),
                pending.summaryJson(), pending.dependenciesJson(), pending.provenanceJson(), pending.schemaVersion(),
                pending.promptVersion(), pending.modelIdentity(), "published-run", pending.gapReason());
        when(persistence.contextCheckpoint(7, "conversation", 1)).thenReturn(checkpoint);
        when(persistence.run(7, "published-run")).thenReturn(new ChatPersistenceService.Run(
                "published-run", "conversation", 7, "published-request", "COMPLETED", null, "旧请求"));
        return checkpoint;
    }

    @Test void revokedCheckpointIsEntirelyDiscardedBeforeAnySubsequentHttpBody() throws Exception {
        semanticHistory();
        server.enqueueSummary(semanticSummary().replace("每天30分钟", "STALE_CHECKPOINT_SENTINEL"), true);
        server.enqueue(List.of("首次回答。[E1]"), "stop", true, 0);
        var initial = prepare(ignored -> { });
        publishMockCheckpoint(initial.pendingCheckpoint());
        when(store.currentChunks(List.of(11L))).thenReturn(List.of());
        var current = new KnowledgeRetrievalService.Source("10", 2, "12", "1", "2", "新材料", "新版本", 0, "新版本只使用当前授权材料。", 1);
        when(store.currentChunks(List.of(12L))).thenReturn(List.of(new CurrentKnowledgeChunk(
                12, 10, 2, 1, 2, current.title(), 0, current.heading(), current.text())));
        when(retrieval.retrieve(eq(7L), eq(1L), anyString(), anyInt())).thenReturn(
                new KnowledgeRetrievalService.Result("current", List.of(current), false, false));
        server.enqueue(List.of("使用新版本回答。[E1]"), "stop", true, 0);
        var answer = prepare(ignored -> { });
        assertThat(server.requests()).hasSize(3);
        assertThat(server.requests().get(2)).contains(current.text()).doesNotContain("STALE_CHECKPOINT_SENTINEL", "OLD_PREFIX_MARKER", source.text());
        assertThat(answer.pendingCheckpoint()).isNull();
        assertThat(answer.usageJson()).doesNotContain("CHECKPOINT_RESTORED");
    }

    @Test void unknownSummaryUsageDegradesOnceAndRemainsPartialAfterAnswer() throws Exception {
        semanticHistory();
        server.enqueueSummary(semanticSummary(), false);
        server.enqueue(List.of("未知摘要用量后的回答。[E1]"), "stop", true, 0);
        var answer = prepare(ignored -> { });
        assertThat(server.requestCount()).isEqualTo(2);
        assertThat(answer.usageJson()).contains("PARTIAL", "SUMMARY_FAILED");
        verify(guard).tryReserveSummary(anyInt(), eq(512), anyInt(), eq(512));
        verify(guard).reserveModel(anyInt(), eq(512));
        verify(guard).recordUsage(3, 2, 5);
    }

    @Test void insufficientCallBudgetSkipsSummaryAndUsesOneAnswerRequest() throws Exception {
        semanticHistory();
        limits.setMaxModelCalls(1);
        server.enqueue(List.of("单次回答。[E1]"), "stop", true, 0);
        var answer = prepare(ignored -> { });
        assertThat(server.requestCount()).isOne();
        assertThat(answer.usageJson()).contains("SUMMARY_BUDGET", "\"summaryCalls\":0");
        verify(guard).reserveModel(anyInt(), eq(512));
    }

    @Test void failedMiddleRoundStopsOlderPrefixInsteadOfClaimingContinuousCoverageAcrossTheGap() throws Exception {
        semanticHistory();
        String dependencies = mapper.writeValueAsString(List.of(source));
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", "OLD_PREFIX_MARKER", "old-1", "[]"),
                message(2, "ASSISTANT", "旧回答。[E1]", "old-1", dependencies),
                message(3, "USER", "失败回合", "failed", "[]"),
                new ChatPersistenceService.Message("m4", 4, "ASSISTANT", "失败内容", "FAILED", "failed", dependencies),
                message(5, "USER", "近期问题一", "recent-1", "[]"),
                message(6, "ASSISTANT", "近期回答一。[E1]", "recent-1", dependencies),
                message(7, "USER", "近期问题二", "recent-2", "[]"),
                message(8, "ASSISTANT", "近期回答二。[E1]", "recent-2", dependencies)));
        server.enqueue(List.of("连续近期回答。[E1]"), "stop", true, 0);
        var answer = prepare(ignored -> { });
        assertThat(server.requestCount()).isOne();
        assertThat(server.requests().get(0)).doesNotContain("OLD_PREFIX_MARKER", "失败内容");
        assertThat(answer.usageJson()).contains("\"summaryCalls\":0", "\"coveredThroughSeq\":0");
    }

    @Test void noMaterialHistoryNeverSpendsASummaryCallToProduceServerRefusal() throws Exception {
        semanticHistory();
        when(retrieval.retrieve(eq(7L), eq(1L), anyString(), anyInt()))
                .thenReturn(new KnowledgeRetrievalService.Result("empty", List.of(), false, false));
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", "旧问题一", "one", "[]"), message(2, "ASSISTANT", "资料不足", "one", "[]"),
                message(3, "USER", "旧问题二", "two", "[]"), message(4, "ASSISTANT", "资料不足", "two", "[]"),
                message(5, "USER", "旧问题三", "three", "[]"), message(6, "ASSISTANT", "资料不足", "three", "[]")));
        var answer = prepare(ignored -> { });
        assertThat(server.requestCount()).isZero();
        assertThat(answer.usageJson()).contains("NO_MODEL", "NO_MATERIAL_CANDIDATE", "\"summaryCalls\":0");
        verify(guard, org.mockito.Mockito.never()).tryReserveSummary(anyInt(), anyInt(), anyInt(), anyInt());
        verify(guard, org.mockito.Mockito.never()).reserveModel(anyInt(), anyInt());
    }

    @Test void necessaryInputOverWindowRefusesWithoutSummaryOrAnswerHttp() throws Exception {
        semanticHistory();
        limits.setModelWindowTokens(900);
        assertThatThrownBy(() -> prepare(ignored -> { }))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getCode()).isEqualTo("AI_MODEL_CONTEXT_WINDOW_EXCEEDED"));
        assertThat(server.requestCount()).isZero();
        verify(guard, org.mockito.Mockito.never()).tryReserveSummary(anyInt(), anyInt(), anyInt(), anyInt());
    }

    private void semanticHistory() throws Exception {
        limits.setContextRedesignEnabled(true);
        limits.setMaxRunTokens(100000); // 仅此 Mock fixture；不改变生产默认额度。
        limits.setContextSoftPressure(0.2);
        limits.setContextTargetPressure(0.1);
        String dependencies = mapper.writeValueAsString(List.of(source));
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", "比较互斥锁和逻辑过期 OLD_PREFIX_MARKER", "old-1", "[]"),
                message(2, "ASSISTANT", "先比较两种方案。[E1]", "old-1", dependencies),
                message(3, "USER", "否定互斥锁，选择逻辑过期，每天30分钟。", "old-2", "[]"),
                message(4, "ASSISTANT", "下一步先做练习。[E1]", "old-2", dependencies),
                message(5, "USER", "进行第二步练习", "recent-1", "[]"),
                message(6, "ASSISTANT", "第二步练习说明。[E1]", "recent-1", dependencies),
                message(7, "USER", "继续查进度", "recent-2", "[]"),
                message(8, "ASSISTANT", "近期回答。[E1]", "recent-2", dependencies)));
        var modelCalls = new AtomicInteger();
        var records = new AtomicInteger();
        var reserved = new java.util.concurrent.atomic.AtomicLong();
        var actual = new java.util.concurrent.atomic.AtomicLong();
        var prepaidInput = new AtomicInteger();
        var prepaidOutput = new AtomicInteger();
        org.mockito.Mockito.when(guard.tryReserveSummary(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(call -> {
            modelCalls.incrementAndGet();
            reserved.addAndGet((int) call.getArgument(0) + (int) call.getArgument(1)
                    + (int) call.getArgument(2) + (int) call.getArgument(3));
            prepaidInput.set(call.getArgument(2)); prepaidOutput.set(call.getArgument(3));
            return true;
        });
        org.mockito.Mockito.doAnswer(call -> {
            modelCalls.incrementAndGet();
            if (prepaidOutput.get() > 0) {
                assertThat((int) call.getArgument(0)).isLessThanOrEqualTo(prepaidInput.get());
                prepaidInput.set(0); prepaidOutput.set(0);
            } else reserved.addAndGet((int) call.getArgument(0) + (int) call.getArgument(1));
            return null;
        }).when(guard).reserveModel(anyInt(), anyInt());
        org.mockito.Mockito.doAnswer(call -> { records.incrementAndGet(); actual.addAndGet((long) call.getArgument(2)); return null; })
                .when(guard).recordUsage(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        when(guard.summary()).thenAnswer(ignored -> new AssistantRunSummary(stored.id(), modelCalls.get(), 0, 0,
                reserved.get(), 0, 0, actual.get(), records.get() == modelCalls.get() && prepaidOutput.get() == 0,
                "RUNNING", 1, prepaidInput.get(), prepaidOutput.get()));
    }

    private String semanticSummary() throws Exception {
        var item = Map.of("value", "每天30分钟", "sourceMessageSeqs", List.of(3), "dependencyKeys", List.of(), "inferred", false);
        Map<String, Object> task = new java.util.LinkedHashMap<>();
        for (String field : List.of("goal", "topic", "constraints", "decisions", "corrections", "openQuestions", "nextStep")) task.put(field, List.of());
        task.put("constraints", List.of(item));
        task.put("decisions", List.of(Map.of("value", "选择逻辑过期", "sourceMessageSeqs", List.of(3), "dependencyKeys", List.of(), "inferred", false)));
        task.put("corrections", List.of(Map.of("value", "否定互斥锁", "sourceMessageSeqs", List.of(3), "dependencyKeys", List.of(), "inferred", false)));
        return mapper.writeValueAsString(Map.of("schemaVersion", 1, "taskState", task,
                "conversationSummary", List.of(), "unresolvedReferences", List.of()));
    }

    @BeforeEach
    void setup() throws Exception {
        limits.setModelWindowTokens(24000); // 本地 HTTP Mock 的显式窗口，不是供应商规格。
        server = new LocalSseServer();
        properties.setMode("DEEPSEEK");
        properties.setApiKey("local_mock_key");
        properties.setModel("deepseek-flash");
        properties.setRequestTimeout(Duration.ofSeconds(5));
        properties.setMaxOutputTokens(512);
        limits.setMaxOutputTokens(512);
        when(guard.remaining()).thenReturn(Duration.ofSeconds(5));
        when(guard.summary()).thenReturn(new AssistantRunSummary(
                stored.id(), 1, 0, 0, 4096, 3, 2, 5, true, "RUNNING", 1));
        when(persistence.conversation(7, "conversation")).thenReturn(
                new ChatPersistenceService.Conversation("conversation", 7, 1, "缓存", 5));
        when(persistence.contextEvents(eq(7L), eq("conversation"), anyLong(), anyInt())).thenAnswer(call -> {
            long after = call.getArgument(2); int limit = call.getArgument(3);
            var rows = persistence.messages(7, "conversation", Long.MAX_VALUE, 48);
            return rows.stream().filter(row -> row.seq() > after).limit(limit).map(row -> {
                var user = rows.stream().filter(item -> item.runId().equals(row.runId()) && "USER".equals(item.role())).findFirst().orElse(row);
                var assistant = rows.stream().filter(item -> item.runId().equals(row.runId()) && "ASSISTANT".equals(item.role())).findFirst().orElse(null);
                return new ContextCheckpoint.Event(row, assistant == null ? "CANCELLED" : assistant.status(), user.id(), assistant == null ? null : assistant.id());
            }).toList();
        });
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L));
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(currentChunk()));
        when(qa.evidenceBook(7L, 1L)).thenAnswer(call -> new EvidenceBook(7L, 1, store, courses, mapper));
        when(retrieval.retrieve(eq(7L), eq(1L), anyString(), eq(5))).thenReturn(
                new KnowledgeRetrievalService.Result("retrieval", List.of(source), false, false));
        var history = new ChatHistoryService(persistence, courses, qa, mapper);
        generator = new LangChainChatGenerator(persistence, history, retrieval, qa, mapper,
                properties, limits, server.endpoint());
    }

    @AfterEach
    void closeServer() {
        try { generator.close(); }
        finally { server.close(); }
    }

    @Test
    void streamsRealDeltasRebuildsBoundedMemoryAndSendsRequiredProtocolFlags() throws Exception {
        var historicalSource = new KnowledgeRetrievalService.Source(source.documentId(), source.sourceVersion(),
                source.chunkId(), source.courseId(), source.lessonId(), source.title(), source.heading(),
                source.chunkOrder(), source.text(), 99);
        String sources = mapper.writeValueAsString(List.of(historicalSource));
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", "最旧问题", "old-1", "[]"),
                message(2, "ASSISTANT", "最旧回答 [E1]", "old-1", sources),
                message(3, "USER", "缓存击穿是什么？", "old-2", "[]"),
                message(4, "ASSISTANT", "同一时刻限制一个重建者。[E1]", "old-2", sources),
                message(5, "USER", "锁应该注意什么？", "old-3", "[]"),
                message(6, "ASSISTANT", "锁必须设置超时。[E1]", "old-3", sources)));
        server.enqueue(List.of("互斥锁会增加等待延迟", "，并需要超时和释放保护。[E1]"), "stop", true, 30);
        List<String> deltas = new CopyOnWriteArrayList<>();

        var answer = prepare(deltas::add);

        assertThat(deltas).hasSizeGreaterThan(1);
        assertThat(answer.text()).isEqualTo("互斥锁会增加等待延迟，并需要超时和释放保护。[E1]");
        assertThat(answer.citations()).singleElement().satisfies(citation ->
                assertThat(citation.evidenceId()).isEqualTo("E1"));
        assertThat(answer.sourcesJson()).contains("\"chunkId\":\"11\"");
        JsonNode request = mapper.readerFor(JsonNode.class)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readValue(server.requests().get(0));
        assertThat(request.path("thinking").path("type").asText()).isEqualTo("disabled");
        assertThat(request.path("stream_options").path("include_usage").asBoolean()).isTrue();
        String requestBody = server.requests().get(0);
        assertThat(requestBody).contains("锁必须设置超时。", "缓存击穿是什么？", "最旧问题", "最旧回答");
        assertThat(requestBody).doesNotContain(
                "同一时刻限制一个重建者。[E1]", "锁必须设置超时。[E1]");
        verify(guard).reserveModel(anyInt(), eq(512));
        verify(guard).recordUsage(3, 2, 5);
        verify(persistence, org.mockito.Mockito.times(2))
                .recordUsage(eq(7L), eq(stored.id()), eq(stored.leaseOwner()), anyString());
        assertThat(server.requestCount()).isOne();
    }

    @Test
    void dropsOversizedHistoryAsAWholeWithoutLosingCurrentEvidence() throws Exception {
        String sources = mapper.writeValueAsString(List.of(source));
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", "庞大历史标记".repeat(1500), "old", "[]"),
                message(2, "ASSISTANT", "庞大回答标记。[E1]", "old", sources),
                message(3, "USER", "缓存怎么重建？", "recent", "[]"),
                message(4, "ASSISTANT", "近期回答。[E1]", "recent", sources)));
        server.enqueue(List.of("当前回答。[E1]"), "stop", true, 0);
        prepare(text -> { });
        assertThat(server.requests().get(0)).contains("近期回答", "它有什么局限？", source.text(), "conversationSummary", "USER_QUOTE")
                .doesNotContain("庞大历史标记".repeat(1500), "庞大回答标记");
    }

    @Test
    void inaccessibleHistoricalTurnCannotContaminateReferenceQueryOrModelInput() throws Exception {
        var obsolete = new KnowledgeRetrievalService.Source("9", 99, "11", "1", "2", source.title(),
                source.heading(), 0, "失效资料", 1);
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", "失效主体标记", "old", "[]"),
                message(2, "ASSISTANT", "失效回答标记。[E1]", "old", mapper.writeValueAsString(List.of(obsolete)))));
        server.enqueue(List.of("回答。[E1]"), "stop", true, 0);
        prepare(text -> { });
        verify(retrieval).retrieve(7L, 1, stored.message(), 5);
        assertThat(server.requests().get(0)).doesNotContain("失效主体标记", "失效回答标记", "失效资料");
    }

    @Test
    void synopsisIsRebuiltFromVerifiedHistoryAndDisappearsWhenItsMaterialChanges() throws Exception {
        String longUser = "我希望先学习缓存失效；" + "重复说明".repeat(1800);
        String sources = mapper.writeValueAsString(List.of(source));
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", longUser, "old", "[]"),
                message(2, "ASSISTANT", "不应摘录的助手推断。[E1]", "old", sources),
                message(3, "USER", "缓存是什么", "recent", "[]"),
                message(4, "ASSISTANT", "近期回答", "recent", "[]")));
        server.enqueue(List.of("回答。[E1]"), "stop", true, 0);
        prepare(text -> { });
        String first = server.requests().get(0);
        assertThat(first).contains("我希望先学习缓存失效", "user-extract-v1", "dependencyHash", "USER_QUOTE")
                .doesNotContain("不应摘录的助手推断", longUser);
        var questionNode = mapper.readTree(mapper.readTree(first).path("messages").get(3).path("content").asText());
        assertThat(questionNode.path("conversationSummary").path("userStatements")).hasSize(1);
        // 原始来源 v1 不再存在；下一次从原消息复核，不复用上次摘要或覆盖游标。
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(
                new CurrentKnowledgeChunk(11, 9, 2, 1, 2, source.title(), 0, source.heading(), source.text())));
        when(retrieval.retrieve(eq(7L), eq(1L), anyString(), eq(5))).thenReturn(
                new KnowledgeRetrievalService.Result("empty", List.of(), true, false));
        var answer = prepare(text -> { });
        assertThat(answer.text()).isEqualTo(LangChainChatGenerator.INSUFFICIENT);
        assertThat(server.requests()).hasSize(1);
        verify(guard, org.mockito.Mockito.times(1)).reserveModel(anyInt(), anyInt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void forgettingPreferenceInvalidatesTheEntireSynopsisRound() throws Exception {
        var preference = new com.learnhub.assistant.memory.PreferenceSnapshot(
                "00000000-0000-0000-0000-000000000001", 1, "STYLE", "先看例子");
        var preferenceSource = mock(com.learnhub.assistant.memory.PreferenceMemorySource.class);
        org.springframework.beans.factory.ObjectProvider<com.learnhub.assistant.memory.PreferenceMemorySource> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(preferenceSource);
        when(preferenceSource.current(7, preference.id())).thenReturn(preference);
        var codec = new com.learnhub.assistant.chat.application.ChatEvidenceCodec(null, provider);
        var previousBook = new EvidenceBook(7L, 1, store, courses, mapper);
        previousBook.material(source);
        previousBook.registerPreference(preference, () -> preferenceSource.current(7, preference.id()));
        String dependencies = codec.encode(previousBook);
        generator.close();
        var history = new ChatHistoryService(persistence, courses, qa, mapper, codec);
        generator = new LangChainChatGenerator(persistence, history, retrieval, qa, mapper,
                properties, limits, server.endpoint(), null, codec, null);
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", "旧用户目标标记；" + "长历史".repeat(2500), "old", "[]"),
                message(2, "ASSISTANT", "不保留助手推断。[E1]", "old", dependencies)));
        server.enqueue(List.of("回答。[E1]"), "stop", true, 0);
        prepare(text -> { });
        assertThat(server.requests().get(0)).contains("USER_QUOTE", "旧用户目标标记").doesNotContain("不保留助手推断");
        when(preferenceSource.current(7, preference.id())).thenReturn(null);
        when(retrieval.retrieve(eq(7L), eq(1L), anyString(), eq(5))).thenReturn(
                new KnowledgeRetrievalService.Result("empty", List.of(), true, false));
        assertThat(prepare(text -> { }).text()).isEqualTo(LangChainChatGenerator.INSUFFICIENT);
        assertThat(server.requests()).hasSize(1);
    }

    @Test
    void independentTopicUsesOnlyCurrentQuestionForRetrieval() throws Exception {
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of(
                message(1, "USER", "缓存锁怎么释放？", "old", "[]"),
                message(2, "ASSISTANT", "旧回答", "old", "[]")));
        server.enqueue(List.of("回答。[E1]"), "stop", true, 0);
        prepare(run("new-topic", "解释数据库索引"), guard("new-topic"), text -> { });
        verify(retrieval).retrieve(7L, 1, "解释数据库索引", 5);
    }

    @Test
    void rejectsMissingUsageAndLengthFinishWithoutReturningPreparedAnswer() {
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of());
        server.enqueue(List.of("答案。[E1]"), "stop", false, 0);
        assertThatThrownBy(() -> prepare(text -> { }))
                .isInstanceOf(BusinessException.class).hasMessageContaining("usage");
        server.enqueue(List.of("被截断答案。[E1]"), "length", true, 0);
        assertThatThrownBy(() -> prepare(text -> { }))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未正常结束");
        assertThat(server.requestCount()).isEqualTo(2);
    }

    @Test
    void greetingDoesNotConsumeModelOrPretendCourseEvidenceIsMissing() {
        var greeting = run("greeting", "你好！");
        List<String> deltas = new ArrayList<>();
        var answer = prepare(greeting, guard, deltas::add);
        assertThat(answer.text()).isEqualTo(LangChainChatGenerator.GREETING);
        assertThat(deltas).containsExactly(LangChainChatGenerator.GREETING);
        assertThat(answer.sourcesJson()).isEqualTo("[]");
        assertThat(answer.usageJson()).contains("NO_COURSE_ANSWER");
        verify(guard, org.mockito.Mockito.never()).reserveModel(anyInt(), anyInt());
        org.mockito.Mockito.verifyNoInteractions(retrieval);
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void absentCourseEvidenceUsesFixedRefusalWithoutInventingCitationsOrCallingModel() {
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of());
        when(retrieval.retrieve(eq(7L), eq(1L), anyString(), eq(5))).thenReturn(
                new KnowledgeRetrievalService.Result("empty", List.of(), true, false));
        var answer = prepare(text -> { });
        assertThat(answer.text()).isEqualTo(LangChainChatGenerator.INSUFFICIENT);
        assertThat(answer.sourcesJson()).isEqualTo("[]");
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.usageJson()).contains("NO_SOURCES_SERVER");
        verify(guard, org.mockito.Mockito.never()).reserveModel(anyInt(), anyInt());
        assertThat(server.requestCount()).isZero();
    }

    @Test
    void nonemptyWeakSourcesCanProduceModelRefusalWithoutPublishingThemAsSupport() {
        server.enqueue(List.of(LangChainChatGenerator.INSUFFICIENT), "stop", true, 0);
        var answer = prepare(run("weak", "课程作者使用哪个私人手机号？"), guard("weak"), text -> { });
        assertThat(answer.text()).isEqualTo(LangChainChatGenerator.INSUFFICIENT);
        assertThat(answer.citations()).isEmpty();
        assertThat(answer.usageJson()).contains("MODEL_REPORTED_INSUFFICIENT");
        // 用量仍计入模型调用；历史保留依赖用于失效复核，但不展示为答案来源。
        assertThat(answer.sourcesJson()).contains("chunkId");
        assertThat(server.requestCount()).isOne();
    }

    @Test
    void externalCancellationAfterFirstDeltaStopsWithoutLateDelta() {
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of());
        server.enqueue(List.of("第一段", "迟到段落。[E1]"), "stop", true, 250);
        List<String> deltas = new CopyOnWriteArrayList<>();

        assertThatThrownBy(() -> prepare(text -> {
            deltas.add(text);
            AssistantIoScope.current().cancel();
        })).isInstanceOf(BusinessException.class).hasMessageContaining("取消");

        assertThat(deltas).containsExactly("第一段");
    }

    @Test
    void sdkWrappedHttpErrorsKeepSafeStatusClassificationWithoutRetryOrProviderText() {
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of());
        int[] statuses = {401, 429, 400, 500};
        String[] codes = {"AI_MODEL_AUTHENTICATION_FAILED", "AI_MODEL_RATE_LIMITED",
                "AI_MODEL_REQUEST_REJECTED", "AI_MODEL_PROVIDER_ERROR"};
        for (int index = 0; index < statuses.length; index++) {
            server.enqueueError(statuses[index]);
            String expected = codes[index];
            assertThatThrownBy(() -> prepare(text -> { })).isInstanceOfSatisfying(BusinessException.class,
                    failure -> assertThat(failure.getCode()).isEqualTo(expected))
                    .hasMessageNotContaining("provider-private-sentinel");
        }
        assertThat(server.requestCount()).isEqualTo(4);
    }

    @Test
    void materialRevocationBetweenDeltaBatchesDiscardsAnswer() {
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of());
        server.enqueue(List.of("第一段", "第二段。[E1]"), "stop", true, 200);
        AtomicBoolean first = new AtomicBoolean();

        assertThatThrownBy(() -> prepare(text -> {
            if (first.compareAndSet(false, true)) when(store.currentChunks(List.of(11L))).thenReturn(List.of());
        })).isInstanceOf(BusinessException.class).hasMessageContaining("已废弃");
    }

    @Test
    void consecutiveRunsReuseSharedPoolsAndGeneratorCloseStopsTheirThreads() throws Exception {
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of());
        server.enqueue(List.of("第一次回答。[E1]"), "stop", true, 0);
        server.enqueue(List.of("第二次回答。[E1]"), "stop", true, 0);

        prepare(text -> { });
        prepare(run("run-second", "第二个问题"), guard("run-second"), text -> { });

        assertThat(poolPrefixes("learnhub-lc4j-network-")).hasSize(1);
        assertThat(poolPrefixes("learnhub-lc4j-reader-")).hasSize(1);
        generator.close();
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!transportThreads().isEmpty() && System.nanoTime() < limit) Thread.sleep(20);
        assertThat(transportThreads()).isEmpty();
    }

    @Test
    void cancellingOneParallelRunDoesNotCancelAnotherRun() throws Exception {
        when(persistence.messages(7, "conversation", Long.MAX_VALUE, 48)).thenReturn(List.of());
        server.enqueue(List.of("A首段", "A迟到段。[E1]"), "stop", true, 250);
        server.enqueue(List.of("B完整回答。[E1]"), "stop", true, 0);
        var runA = run("run-a", "并行问题A");
        var runB = run("run-b", "并行问题B");
        var guardA = guard(runA.id());
        var guardB = guard(runB.id());
        List<String> deltasA = new CopyOnWriteArrayList<>();
        List<String> deltasB = new CopyOnWriteArrayList<>();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Throwable> cancelled = CompletableFuture.supplyAsync(() -> {
                try {
                    prepare(runA, guardA, text -> {
                        deltasA.add(text);
                        AssistantIoScope.current().cancel();
                    });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            }, workers);
            assertThat(server.awaitRequests(1, 2, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<LangChainChatGenerator.PreparedAnswer> completed = CompletableFuture.supplyAsync(
                    () -> prepare(runB, guardB, deltasB::add), workers);

            assertThat(cancelled.get(3, TimeUnit.SECONDS)).isInstanceOf(BusinessException.class);
            assertThat(completed.get(3, TimeUnit.SECONDS).text()).isEqualTo("B完整回答。[E1]");
            assertThat(deltasA).containsExactly("A首段");
            assertThat(deltasB).containsExactly("B完整回答。[E1]");
        } finally {
            workers.shutdownNow();
        }
    }

    private LangChainChatGenerator.PreparedAnswer prepare(java.util.function.Consumer<String> delta) {
        return prepare(stored, guard, delta);
    }

    private LangChainChatGenerator.PreparedAnswer prepare(ChatPersistenceService.Run run,
            AssistantRunGuard.Run runGuard, java.util.function.Consumer<String> delta) {
        try (var io = new AssistantIoScope(Duration.ofSeconds(5))) {
            io.attach();
            return generator.prepare(7, run, runGuard, delta);
        }
    }

    private ChatPersistenceService.Run run(String id, String question) {
        return new ChatPersistenceService.Run(id, "conversation", 7, id, "RUNNING", "lease-" + id, question);
    }

    private AssistantRunGuard.Run guard(String id) {
        AssistantRunGuard.Run result = mock(AssistantRunGuard.Run.class);
        when(result.remaining()).thenReturn(Duration.ofSeconds(5));
        when(result.summary()).thenReturn(new AssistantRunSummary(
                id, 1, 0, 0, 4096, 3, 2, 5, true, "RUNNING", 1));
        return result;
    }

    private static List<String> poolPrefixes(String prefix) {
        return transportThreads().stream().filter(name -> name.startsWith(prefix))
                .map(name -> name.replaceFirst("-\\d+$", "")).distinct().toList();
    }

    private static List<String> transportThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.startsWith("learnhub-lc4j-network-")
                        || name.startsWith("learnhub-lc4j-reader-"))
                .toList();
    }

    private ChatPersistenceService.Message message(long seq, String role, String content, String run, String sources) {
        return new ChatPersistenceService.Message("m" + seq, seq, role, content, "COMPLETED", run, sources);
    }

    private CurrentKnowledgeChunk currentChunk() {
        return new CurrentKnowledgeChunk(11, 9, 1, 1, 2, source.title(), 0, source.heading(), source.text());
    }

    private static final class LocalSseServer implements AutoCloseable {
        private final HttpServer server;
        private final List<Response> responses = new ArrayList<>();
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final AtomicInteger requestCount = new AtomicInteger();
        private final ExecutorService handlers = Executors.newFixedThreadPool(4);

        LocalSseServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/v1/chat/completions", this::handle);
            server.setExecutor(handlers);
            server.start();
        }

        synchronized void enqueue(List<String> deltas, String finish, boolean usage, long delayMillis) {
            responses.add(new Response(List.copyOf(deltas), finish, usage, delayMillis, 200, false));
        }

        synchronized void enqueueSummary(String summary, boolean usage) {
            responses.add(new Response(List.of(summary), "stop", usage, 0, 200, true));
        }

        synchronized void enqueueError(int status) {
            responses.add(new Response(List.of(), null, false, 0, status, false));
        }

        URI endpoint() {
            return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/v1");
        }

        List<String> requests() { return requests; }
        int requestCount() { return requestCount.get(); }

        boolean awaitRequests(int count, long timeout, TimeUnit unit) throws InterruptedException {
            long until = System.nanoTime() + unit.toNanos(timeout);
            while (requestCount.get() < count && System.nanoTime() < until) Thread.sleep(10);
            return requestCount.get() >= count;
        }

        private void handle(HttpExchange exchange) throws IOException {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestCount.incrementAndGet();
            Response response;
            synchronized (this) { response = responses.remove(0); }
            if (response.status() != 200) {
                byte[] error = "{\"error\":{\"message\":\"provider-private-sentinel\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.status(), error.length);
                try (var output = exchange.getResponseBody()) { output.write(error); }
                return;
            }
            if (response.synchronous()) {
                Map<String, Object> payload = new java.util.LinkedHashMap<>(java.util.Map.of("id", "summary", "model", "deepseek-flash",
                        "choices", List.of(java.util.Map.of("index", 0, "message", java.util.Map.of("role", "assistant", "content", response.deltas().get(0)),
                                "finish_reason", "stop"))));
                if (response.usage()) payload.put("usage", java.util.Map.of("prompt_tokens", 3, "completion_tokens", 2, "total_tokens", 5));
                byte[] bytes = new ObjectMapper().writeValueAsBytes(payload);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (var output = exchange.getResponseBody()) { output.write(bytes); }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                int index = 0;
                for (String delta : response.deltas()) {
                    write(output, "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                            + "\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{" + (index++ == 0 ? "\"role\":\"assistant\"," : "")
                            + "\"content\":" + quote(delta) + "},\"finish_reason\":null}]}\n\n");
                    pause(response.delayMillis());
                }
                String usage = response.usage()
                        ? ",\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}" : "";
                write(output, "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                        + "\"created\":1,\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{},\"finish_reason\":\"" + response.finish() + "\"}]" + usage + "}\n\n");
                write(output, "data: [DONE]\n\n");
            } catch (IOException disconnected) {
                // 取消用例会主动关闭响应体。
            }
        }

        private static void write(java.io.OutputStream output, String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }

        private static String quote(String value) {
            try { return new ObjectMapper().writeValueAsString(value); }
            catch (Exception impossible) { throw new AssertionError(impossible); }
        }

        private static void pause(long millis) {
            if (millis <= 0) return;
            try { Thread.sleep(millis); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }

        @Override public void close() { server.stop(0); handlers.shutdownNow(); }
        private record Response(List<String> deltas, String finish, boolean usage, long delayMillis, int status, boolean synchronous) { }
    }
}
