package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.knowledge.*;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.qa.*;
import com.learnhub.assistant.runtime.*;
import com.learnhub.course.service.CourseService;
import java.net.URI;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 明确开启才执行付费烟测。课程/权限/历史是受控fixture，不冒充真实ES或浏览器课程验收。 */
@EnabledIfSystemProperty(named = "learnhub.langchain.live.enabled", matches = "true")
@Timeout(100)
class LangChainLiveSmokeIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path LEDGER = Path.of("..", ".local", "langchain-phase3-live-ledger.json");
    private static final ModelBatchLedger BATCH_LEDGER = new ModelBatchLedger(LEDGER, 3, 12_000);

    @Test void boundedRealStreamingFollowupAndCancellation() throws Exception {
        String key = System.getenv("LEARNHUB_LANGCHAIN_LIVE_KEY");
        assertThat(key != null && key.matches("[A-Za-z0-9_-]{8,256}")).as("本地凭据已通过环境注入").isTrue();
        var properties = new AiProperties(); properties.setMode("DEEPSEEK"); properties.setApiKey(key);
        properties.setMaxOutputTokens(256); properties.setRequestTimeout(Duration.ofSeconds(25));
        var limits = new AssistantLimits(); limits.setTimeout(Duration.ofSeconds(30)); limits.setMaxOutputTokens(256);
        limits.setMaxModelCalls(1); limits.setMaxRunTokens(8_000);
        var guard = AssistantGuardTestSupport.guard(limits, LangChainLiveSmokeIntegrationTest::reserve);
        var persistence = mock(ChatPersistenceService.class); var courses = mock(CourseService.class);
        var retrieval = mock(KnowledgeRetrievalService.class); var qa = mock(CourseQaService.class);
        var store = mock(KnowledgeStore.class);
        var source = new KnowledgeRetrievalService.Source("9", 1, "11", "1", "2", "受控缓存讲义", "互斥重建", 0,
                "热点缓存失效时，可以使用互斥锁限制同一时刻只有一个请求重建缓存。其他请求等待后重新读取缓存。"
                + "互斥锁需要超时与释放保护，锁竞争会增加延迟；重建失败时还需要降级和重试控制。", 1);
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(new CurrentKnowledgeChunk(11, 9, 1, 1, 2,
                source.title(), 0, source.heading(), source.text())));
        when(courses.readableLessonIds(7L, 1L)).thenReturn(List.of(2L));
        when(persistence.conversation(7, "smoke")).thenReturn(new ChatPersistenceService.Conversation("smoke", 7, 1, "烟测", 1));
        when(qa.evidenceBook(7L, 1)).thenAnswer(call -> new EvidenceBook(7L, 1, store, courses, JSON));
        when(retrieval.retrieve(eq(7L), eq(1L), anyString(), anyInt()))
                .thenReturn(new KnowledgeRetrievalService.Result("fixture", List.of(source), false, false));
        var history = new ChatHistoryService(persistence, courses, qa, JSON);
        var generator = new LangChainChatGenerator(persistence, history, retrieval, qa, JSON, properties, limits,
                URI.create("https://api.deepseek.com/v1"));
        try {
        var formal = new ArrayList<ChatPersistenceService.Message>();
        when(persistence.messages(eq(7L), eq("smoke"), anyLong(), anyInt())).thenAnswer(call -> List.copyOf(formal));
        boolean historyFixture = Boolean.getBoolean("learnhub.langchain.live.historyFixture");
        if (historyFixture) {
            // 修复首次失败后仅用剩余额度验证追问/取消，既有失败及预留不清零，不重发原问题。
            formal.add(new ChatPersistenceService.Message("fixture-user", 1, "USER", "怎样避免热点缓存同时重建？", "COMPLETED", "fixture", "[]"));
            formal.add(new ChatPersistenceService.Message("fixture-answer", 2, "ASSISTANT", "可用互斥锁限制同一时刻一个请求重建缓存。[E1]", "COMPLETED", "fixture", JSON.writeValueAsString(List.of(source))));
        }
        String[] questions = {"热点缓存失效后怎样防止大量请求同时重建？请在80字以内回答。", "刚才的方法有什么局限？请在80字以内回答。", "再解释一次互斥重建。"};
        for (int step = historyFixture ? 1 : 0; step < questions.length; step++) {
            String id = UUID.randomUUID().toString();
            var stored = new ChatPersistenceService.Run(id, "smoke", 7, id, "RUNNING", "lease-" + id, questions[step]);
            when(persistence.run(7, id)).thenReturn(stored);
            long started = System.nanoTime(); var firstDeltaMillis = new AtomicLong(-1); var deltas = new AtomicInteger();
            boolean cancel = step == 2;
            try (var io = new AssistantIoScope(Duration.ofSeconds(30))) {
                io.attach();
                try (var run = guard.open(7L, id, io::remaining)) {
                    try {
                        var prepared = generator.prepare(7, stored, run, text -> {
                            deltas.incrementAndGet(); firstDeltaMillis.compareAndSet(-1, Duration.ofNanos(System.nanoTime() - started).toMillis());
                            if (cancel) { guard.cancel(7L, id); io.cancel(); }
                        });
                        assertThat(cancel).as("取消后不应返回成功答案").isFalse();
                        assertThat(prepared.text()).isNotBlank(); assertThat(deltas.get()).isGreaterThan(1);
                        assertThat(run.summary().usageComplete()).isTrue();
                        outcome("COMPLETED", run.summary(), firstDeltaMillis.get(), deltas.get(), step);
                        formal.add(new ChatPersistenceService.Message("u-" + id, formal.size() + 1, "USER", questions[step], "COMPLETED", id, "[]"));
                        formal.add(new ChatPersistenceService.Message("a-" + id, formal.size() + 1, "ASSISTANT", prepared.text(), "COMPLETED", id, prepared.sourcesJson()));
                        when(persistence.run(7, id)).thenReturn(new ChatPersistenceService.Run(id, "smoke", 7, id, "COMPLETED", null, questions[step]));
                    } catch (RuntimeException failure) {
                        String code = failure instanceof com.learnhub.common.exception.BusinessException business
                                ? business.getCode() : "UNEXPECTED_RUNTIME";
                        outcome((cancel ? "CANCELLED_OR_FAILED:" : "FAILED:") + code,
                                run.summary(), firstDeltaMillis.get(), deltas.get(), step);
                        if (!cancel) throw new AssertionError("真实模型烟测失败，供应商详情已隐藏");
                        assertThat(firstDeltaMillis.get()).isGreaterThanOrEqualTo(0);
                        assertThat(code).isEqualTo("ASSISTANT_CANCELLED");
                    }
                }
            }
        }
        } finally {
            generator.close();
        }
    }

    private static boolean reserve(int tokens) {
        return BATCH_LEDGER.reserve(tokens);
    }

    private static void outcome(String status, AssistantRunSummary summary, long latency, int deltas, int step) {
        BATCH_LEDGER.recordOutcome(status, summary, latency, deltas, step);
    }
}
