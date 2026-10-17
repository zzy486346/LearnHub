package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.agent.CourseAgentService;
import com.learnhub.assistant.model.ChatModelClient;
import com.learnhub.assistant.model.ChatRequest;
import com.learnhub.assistant.model.ChatResponse;
import com.learnhub.assistant.model.ModelClientException;
import com.learnhub.assistant.model.Usage;
import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.qa.GroundedAnswer;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 显式开启的真实 DeepSeek 验收。仅读取现有业务数据，不创建用户、订单、进度或知识材料。
 * 详细回答只写入被忽略的 .local 文件，控制台只输出调用与 Token 计数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        "learnhub.ai.mode=DEEPSEEK",
        "learnhub.ai.model=deepseek-flash",
        "learnhub.ai.max-output-tokens=512",
        "learnhub.ai.request-timeout=20s",
        "learnhub.ai.connect-timeout=5s",
        "learnhub.assistant.timeout=45s",
        "learnhub.assistant.max-model-calls=4",
        "learnhub.assistant.max-tool-decision-rounds=3",
        "learnhub.assistant.max-tool-calls=4",
        "learnhub.assistant.max-run-tokens=24000",
        "learnhub.assistant.max-output-tokens=512",
        "learnhub.assistant.requests-per-minute=32",
        "learnhub.assistant.daily-model-calls=32",
        "learnhub.assistant.daily-reserved-tokens=240000",
        "learnhub.knowledge.sync-enabled=false",
        "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false",
        "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false",
        "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false"
})
@Import(AssistantLiveAcceptanceIntegrationTest.LiveConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@EnabledIfSystemProperty(named = "learnhub.assistant.live.acceptance", matches = "true")
class AssistantLiveAcceptanceIntegrationTest {
    private static final int MAX_PROVIDER_CALLS = 32;
    private static final long MAX_RESERVED_TOKENS = 240_000;
    private static final long MAX_ACTUAL_TOKENS = 120_000;
    private static final String REDIS_PREFIX = "learnhub:assistant:{live-" + UUID.randomUUID() + "}:";
    private static final Path REPOSITORY_ROOT = repositoryRoot();
    private static final Path SMOKE_REPORT = REPOSITORY_ROOT.resolve(".local/assistant-live-smoke.json");
    private static final Path HOLDOUT_REPORT = REPOSITORY_ROOT.resolve(".local/assistant-live-holdout.json");

    @Autowired private CourseAgentService agent;
    @Autowired private CourseQaService qa;
    @Autowired private AssistantRunGuard guard;
    @Autowired private CourseService courses;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private StringRedisTemplate redis;
    @Autowired private CountingChatModelClient liveModel;

    private final AtomicLong totalReservedTokens = new AtomicLong();
    private final List<Map<String, Object>> results = new ArrayList<>();

    @org.junit.jupiter.api.AfterAll
    void clearOwnedRedisNamespace() {
        Set<String> keys = redis.keys(REDIS_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    @Test
    @Order(1)
    @EnabledIfSystemProperty(named = "learnhub.assistant.live.smoke", matches = "true")
    void runsBoundedLiveRagAndAgentSmoke() throws Exception {
        requireEnvironmentCredential();
        clearOwnedRedisNamespace();
        JsonNode evaluation = readEvaluation();
        Map<String, Mapping> mappings = mappings(evaluation);
        int firstResult = results.size();
        boolean passed = false;
        try {
            runLiveRagSmoke(mappings.get("elasticsearch"));
            runLiveAgentSmoke(mappings.get("java-concurrency"));
            assertGlobalLimits();
            assertThat(results.size() - firstResult).isEqualTo(2);
            passed = true;
        } finally {
            writeReport(SMOKE_REPORT, "SMOKE", passed ? "PASSED" : "FAILED", firstResult);
        }
        System.out.printf("ASSISTANT_LIVE_SMOKE providerCalls=%d actualTokens=%d reservedTokens=%d "
                        + "ragSmoke=1 agentSmoke=1 report=%s%n",
                liveModel.calls(), liveModel.actualTokens(), totalReservedTokens.get(), SMOKE_REPORT);
    }

    @Test
    @Order(2)
    @EnabledIfSystemProperty(named = "learnhub.assistant.live.holdout", matches = "true")
    void runsTwentyCaseHoldoutAfterSuccessfulSmoke() throws Exception {
        requireEnvironmentCredential();
        assertThat(previousSmokePassed()).as("必须先单独运行并通过真实 RAG + Agent 烟测阶段").isTrue();
        clearOwnedRedisNamespace();
        JsonNode evaluation = readEvaluation();
        Map<String, Mapping> mappings = mappings(evaluation);
        int firstResult = results.size();
        boolean completed = false;
        try {
            for (JsonNode testCase : evaluation.withArray("cases")) runHoldout(testCase, mappings);
            assertGlobalLimits();
            assertThat(results.size() - firstResult).isEqualTo(20);
            completed = true;
        } finally {
            writeReport(HOLDOUT_REPORT, "HOLDOUT", completed ? "COMPLETED_MANUAL_REVIEW_REQUIRED" : "FAILED",
                    firstResult);
        }
        System.out.printf("ASSISTANT_LIVE_HOLDOUT providerCalls=%d actualTokens=%d reservedTokens=%d "
                        + "holdoutCases=20 report=%s%n",
                liveModel.calls(), liveModel.actualTokens(), totalReservedTokens.get(), HOLDOUT_REPORT);
    }

    private void requireEnvironmentCredential() {
        String apiKey = System.getenv("LEARNHUB_DEEPSEEK_API_KEY");
        Assumptions.assumeTrue(apiKey != null && !apiKey.isBlank(),
                "仅从 LEARNHUB_DEEPSEEK_API_KEY 环境变量读取凭据；未配置时不发起付费请求");
        assertThat(System.getProperty("LEARNHUB_DEEPSEEK_API_KEY")).isNull();
    }

    private void assertGlobalLimits() {
        assertThat(liveModel.calls()).isBetween(2, MAX_PROVIDER_CALLS);
        assertThat(liveModel.actualTokens()).isBetween(1L, MAX_ACTUAL_TOKENS);
        assertThat(totalReservedTokens.get()).isLessThanOrEqualTo(MAX_RESERVED_TOKENS);
    }

    private JsonNode readEvaluation() throws Exception {
        return mapper.readTree(Files.readString(REPOSITORY_ROOT.resolve("docs/assistant-retrieval-evaluation.json")));
    }

    private void runLiveRagSmoke(Mapping mapping) {
        long userId = activeReadableUser(mapping.courseId(), mapping.lessonId());
        Map<String, Object> result = executeQa("LIVE-RAG-001", userId, mapping.courseId(),
                "请结合讲解稿说明：搜索输入的关键词怎样帮助系统缩小候选课程范围？", Map.of(
                        "expectedOutcome", "ANSWER_WITH_CITATION",
                        "expectedSourceKeys", List.of("catalog.elasticsearch.inverted-index")));
        result.put("manualQualityStatus", "PENDING_REVIEW");
        results.add(result);
        assertThat(result.get("status")).isEqualTo("EXECUTED");
        GroundedAnswer answer = (GroundedAnswer) result.remove("_answer");
        assertThat(answer.modelMode()).isEqualTo("DEEPSEEK");
        assertThat(answer.insufficientEvidence()).isFalse();
        assertThat(sourceKeys(answer)).contains("catalog.elasticsearch.inverted-index");
        result.put("answer", answer);
    }

    private void runLiveAgentSmoke(Mapping mapping) {
        long userId = activeReadableUser(mapping.courseId(), mapping.lessonId());
        String requestId = "live-agent-" + UUID.randomUUID();
        Map<String, Object> result = baseResult("LIVE-AGENT-001", "AGENT",
                "结合本人实际学习进度与课程目录，给出下一步学习建议。",
                Map.of("requiredTools", List.of("get_my_learning_progress", "get_course_outline")));
        var run = guard.open(userId, requestId, identityCheck(userId));
        try {
            GroundedAnswer answer = agent.answer(userId, mapping.courseId(),
                    "必须先读取本人实际学习进度和当前课程目录，再给出下一步学习建议。", run);
            assertThat(answer.modelMode()).isEqualTo("DEEPSEEK");
            assertThat(answer.trace()).contains("get_my_learning_progress", "get_course_outline");
            assertThat(answer.answerSegments()).anyMatch(segment -> "SUGGESTION".equals(segment.kind()));
            assertThat(answer.citations()).extracting(citation -> citation.kind())
                    .contains("PROGRESS", "OUTLINE");
            result.put("status", "EXECUTED");
            result.put("trace", answer.trace());
            result.put("answer", answer);
            result.put("manualQualityStatus", "PENDING_REVIEW");
        } catch (RuntimeException failure) {
            result.put("status", "FAILED");
            result.put("error", safeError(failure));
            throw failure;
        } finally {
            addReservation(run.summary().reservedTokens());
            run.close();
            result.put("runUsage", run.summary());
            results.add(result);
        }
    }

    private void runHoldout(JsonNode testCase, Map<String, Mapping> mappings) {
        String id = testCase.path("id").asText();
        String access = testCase.path("access").asText();
        Map<String, Object> expected = Map.of(
                "category", testCase.path("category").asText(),
                "expectedOutcome", testCase.path("expectedOutcome").asText(),
                "expectedSourceKeys", strings(testCase.path("expectedSourceKeys")),
                "expectedTerms", strings(testCase.path("expectedTerms")),
                "forbiddenSourceKeys", strings(testCase.path("forbiddenSourceKeys")));
        if (access.endsWith("_FIXTURE")) {
            Map<String, Object> result = baseResult(id, "HOLDOUT", testCase.path("query").asText(), expected);
            result.put("status", "NOT_EXECUTABLE_WITH_CURRENT_DATA");
            result.put("reason", "规格要求隔离的未购/退款/已删除课时夹具；本验收禁止改写订单、课程或课时数据");
            result.put("manualQualityStatus", "NOT_RUN");
            results.add(result);
            return;
        }

        Mapping mapping = mappings.get(testCase.path("courseSemanticKey").asText());
        long userId = activeReadableUser(mapping.courseId(), mapping.lessonId());
        Map<String, Object> result = executeQa(id, userId, mapping.courseId(), testCase.path("query").asText(), expected);
        GroundedAnswer answer = (GroundedAnswer) result.remove("_answer");
        if (answer != null) result.put("answer", answer);
        result.put("manualQualityStatus", "PENDING_REVIEW");
        results.add(result);
    }

    private Map<String, Object> executeQa(String id, long userId, long courseId, String query,
                                           Map<String, Object> expected) {
        Map<String, Object> result = baseResult(id, "RAG", query, expected);
        int callsBefore = liveModel.calls();
        AssistantRunGuard.Run run = null;
        try {
            run = guard.open(userId, "live-qa-" + id.toLowerCase() + "-" + UUID.randomUUID(),
                    identityCheck(userId));
            GroundedAnswer answer = qa.answer(userId, courseId, query, run);
            result.put("status", "EXECUTED");
            result.put("retrievalSourceKeys", sourceKeys(answer));
            result.put("insufficientEvidence", answer.insufficientEvidence());
            result.put("_answer", answer);
        } catch (ModelClientException providerFailure) {
            result.put("status", "PROVIDER_FAILURE");
            result.put("error", safeError(providerFailure));
            results.add(result);
            throw providerFailure;
        } catch (BusinessException validationOrQualityFailure) {
            result.put("status", "REJECTED_OR_INVALID_OUTPUT");
            result.put("error", safeError(validationOrQualityFailure));
        } finally {
            if (run != null) {
                addReservation(run.summary().reservedTokens());
                run.close();
                result.put("runUsage", run.summary());
            }
            result.put("providerCalls", liveModel.calls() - callsBefore);
        }
        return result;
    }

    private void addReservation(long tokens) {
        long total = totalReservedTokens.addAndGet(tokens);
        assertThat(total).as("真实验收累计保守预留 Token").isLessThanOrEqualTo(MAX_RESERVED_TOKENS);
    }

    private List<String> sourceKeys(GroundedAnswer answer) {
        return answer.citations().stream().filter(citation -> citation.documentId() != null)
                .map(citation -> jdbc.queryForObject("SELECT source_key FROM knowledge_document WHERE id = ?",
                        String.class, Long.parseLong(citation.documentId())))
                .distinct().toList();
    }

    private long activeReadableUser(long courseId, long lessonId) {
        List<Long> activeUsers = jdbc.queryForList(
                "SELECT id FROM user WHERE status = 'ACTIVE' AND deleted = 0 ORDER BY id", Long.class);
        for (Long userId : activeUsers) {
            try {
                if (courses.readableLessonIds(userId, courseId).contains(lessonId)) return userId;
            } catch (RuntimeException ignored) {
                // 继续寻找真实可读用户；不创建或修改权限数据。
            }
        }
        throw new AssertionError("没有 ACTIVE 用户可读取课程 " + courseId + " 的评测材料");
    }

    private Runnable identityCheck(long userId) {
        return () -> assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user WHERE id = ? AND status = 'ACTIVE' AND deleted = 0",
                Integer.class, userId)).isOne();
    }

    private Map<String, Mapping> mappings(JsonNode evaluation) {
        Map<String, Mapping> result = new LinkedHashMap<>();
        for (JsonNode node : evaluation.withArray("sourceMappings")) {
            result.putIfAbsent(node.path("courseSemanticKey").asText(), new Mapping(
                    node.path("courseId").asLong(), node.path("lessonId").asLong()));
        }
        return result;
    }

    private Map<String, Object> baseResult(String id, String type, String input, Map<String, Object> expected) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("type", type);
        result.put("input", input);
        result.put("expected", expected);
        result.put("retrievalSourceKeys", List.of());
        result.put("answer", null);
        result.put("runUsage", null);
        result.put("providerCalls", 0);
        return result;
    }

    private void writeReport(Path path, String phase, String phaseStatus, int firstResult) throws Exception {
        Files.createDirectories(path.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("phase", phase);
        report.put("phaseStatus", phaseStatus);
        report.put("model", "deepseek-flash");
        report.put("maxProviderCalls", MAX_PROVIDER_CALLS);
        report.put("providerCalls", liveModel.calls());
        report.put("maxReservedTokens", MAX_RESERVED_TOKENS);
        report.put("reservedTokens", totalReservedTokens.get());
        report.put("maxActualTokens", MAX_ACTUAL_TOKENS);
        report.put("actualTokens", liveModel.actualTokens());
        report.put("qualityDecision", "MANUAL_REVIEW_REQUIRED");
        report.put("providerResponses", liveModel.responses);
        report.put("cases", List.copyOf(results.subList(firstResult, results.size())));
        mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    }

    private boolean previousSmokePassed() {
        try {
            return Files.isRegularFile(SMOKE_REPORT)
                    && "PASSED".equals(mapper.readTree(Files.readString(SMOKE_REPORT)).path("phaseStatus").asText());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Map<String, Object> safeError(RuntimeException failure) {
        String code = failure instanceof BusinessException business ? business.getCode()
                : failure.getClass().getSimpleName();
        return Map.of("code", code == null ? "UNKNOWN" : code);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("docs/assistant-retrieval-evaluation.json"))) return candidate;
        }
        throw new IllegalStateException("无法定位 LearnHub 仓库根目录");
    }

    private record Mapping(long courseId, long lessonId) { }

    static final class CountingChatModelClient implements ChatModelClient {
        private final ChatModelClient delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong actualTokens = new AtomicLong();
        private final List<ChatResponse> responses = new ArrayList<>();

        CountingChatModelClient(ChatModelClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse generate(ChatRequest request) {
            int call = calls.incrementAndGet();
            if (call > MAX_PROVIDER_CALLS) throw new AssertionError("真实模型调用次数超过硬上限");
            assertThat(request.maxOutputTokens()).isLessThanOrEqualTo(800);
            try {
                ChatResponse response = delegate.generate(request);
                responses.add(response);
                record(response.usage());
                return response;
            } catch (ModelClientException failure) {
                if (failure.usage() != null) record(failure.usage());
                throw failure;
            }
        }

        private void record(Usage usage) {
            long total = actualTokens.addAndGet(usage.totalTokens());
            if (total > MAX_ACTUAL_TOKENS) throw new AssertionError("真实模型实际 Token 超过硬上限");
        }

        int calls() { return calls.get(); }
        long actualTokens() { return actualTokens.get(); }
    }

    @TestConfiguration
    static class LiveConfiguration {
        @Bean
        @Primary
        AssistantRunStore liveAssistantRunStore(StringRedisTemplate redis) {
            return new RedisAssistantRunStore(redis, REDIS_PREFIX);
        }

        @Bean
        @Primary
        CountingChatModelClient countingChatModelClient(
                @Qualifier("chatModelClient") ChatModelClient delegate) {
            return new CountingChatModelClient(delegate);
        }
    }
}
