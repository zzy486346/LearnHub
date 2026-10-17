package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.agent.CourseAgentService;
import com.learnhub.assistant.model.ChatMessage;
import com.learnhub.assistant.model.ChatModelClient;
import com.learnhub.assistant.model.ChatRequest;
import com.learnhub.assistant.model.ChatResponse;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.assistant.model.Usage;
import com.learnhub.assistant.qa.GroundedAnswer;
import com.learnhub.course.service.CourseService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfSystemProperty(named = "learnhub.assistant.scenario.it", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "spring.config.location=file:src/main/resources/application.yml",
        "learnhub.ai.mode=FAKE",
        "learnhub.knowledge.sync-enabled=false",
        "learnhub.search.sync-enabled=false",
        "learnhub.messaging.enabled=false",
        "learnhub.seckill.reconciliation-enabled=false",
        "learnhub.like.flush-enabled=false",
        "learnhub.like.publish-retry-enabled=false",
        "learnhub.like.reconciliation-enabled=false"
})
@Import(AssistantScenarioIntegrationTest.ScenarioRedisConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AssistantScenarioIntegrationTest {
    private static final long COURSE_ID = 12002L;
    private static final long MATERIAL_LESSON_ID = 32021L;
    private static final String REDIS_PREFIX =
            "learnhub:assistant:{scenario-" + UUID.randomUUID() + "}:";
    private static final AtomicInteger TOTAL_TOOL_CALLS = new AtomicInteger();
    private static final AtomicInteger TOTAL_MOCK_MODEL_CALLS = new AtomicInteger();

    @Autowired private CourseAgentService agent;
    @Autowired private AssistantRunGuard guard;
    @Autowired private CourseService courses;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private StringRedisTemplate redis;
    @MockBean private ChatModelClient model;

    private final List<String> issuedTools = new ArrayList<>();
    private final List<ChatRequest> modelRequests = new ArrayList<>();
    private Scenario scenario;
    private String courseTitle;
    private String materialQuery;
    private int modelCalls;

    @BeforeEach
    void prepareScenario() {
        clearScenarioRedis();
        reset(model);
        issuedTools.clear();
        modelRequests.clear();
        modelCalls = 0;
        courseTitle = jdbc.queryForObject("SELECT title FROM course WHERE id = ?", String.class, COURSE_ID);
        materialQuery = jdbc.queryForObject("SELECT title FROM course_lesson WHERE id = ?", String.class,
                MATERIAL_LESSON_ID);
        when(model.generate(any(ChatRequest.class))).thenAnswer(invocation -> scripted(invocation.getArgument(0)));
    }

    @AfterEach
    void cleanScenarioRedis() {
        clearScenarioRedis();
    }

    @AfterAll
    static void reportsExactScenarioExecutionCounts() {
        assertThat(TOTAL_TOOL_CALLS.get()).isEqualTo(5);
        assertThat(TOTAL_MOCK_MODEL_CALLS.get()).isEqualTo(6);
        System.out.println("ASSISTANT_SCENARIO_TOTAL tools=5 mockModelCalls=6 deepSeekCalls=0 holdoutGenerationCases=0");
    }

    @Test
    @Order(1)
    void combinesRealOutlineEmptyProgressAndMaterialsIntoGroundedSuggestion() {
        scenario = Scenario.LEARNING_SUGGESTION;
        long userId = activeUserWithMaterialAccessAndNoProgress();

        GroundedAnswer answer;
        try (var run = guard.open(userId, "scenario-learning-" + UUID.randomUUID(), identityCheck(userId))) {
            answer = agent.answer(userId, COURSE_ID,
                    "结合我在这门课的实际学习进度、课程目录和相关材料，建议下一步如何学习？", run);
        }

        assertThat(issuedTools).containsExactly(
                "get_course_outline", "get_my_learning_progress", "search_course_materials");
        assertThat(modelCalls).isEqualTo(3);
        assertThat(answer.trace()).containsExactlyElementsOf(issuedTools);
        assertThat(answer.modelMode()).isEqualTo("MOCK");
        assertThat(answer.insufficientEvidence()).isFalse();
        assertThat(answer.answerSegments()).extracting(GroundedAnswer.Segment::kind)
                .containsExactly("FACT", "SUGGESTION");
        GroundedAnswer.Segment suggestion = answer.answerSegments().get(1);
        assertThat(suggestion.evidenceIds()).hasSize(2);
        assertThat(answer.citations()).filteredOn(citation -> suggestion.evidenceIds().contains(citation.evidenceId()))
                .extracting(citation -> citation.kind()).containsExactlyInAnyOrder("PROGRESS", "OUTLINE");
        assertThat(answer.citations()).extracting(citation -> citation.kind()).contains("MATERIAL");
        assertThat(answer.usage().modelCalls()).isEqualTo(3);
        assertThat(answer.usage().toolCalls()).isEqualTo(3);
        assertThat(answer.usage().actualPromptTokens()).isEqualTo(30);
        assertThat(answer.usage().actualCompletionTokens()).isEqualTo(30);
        assertThat(answer.usage().actualTotalTokens()).isEqualTo(60);
        assertThat(answer.usage().usageComplete()).isTrue();

        JsonNode progress = evidence("PROGRESS").path("data");
        assertThat(progress.path("courseId").asText()).isEqualTo(Long.toString(COURSE_ID));
        assertThat(progress.path("progress").isArray()).isTrue();
        assertThat(progress.path("progress").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM learning_progress lp
                JOIN course_lesson cl ON cl.id = lp.lesson_id
                JOIN course_chapter cc ON cc.id = cl.chapter_id
                WHERE lp.user_id = ? AND cc.course_id = ? AND cl.deleted = 0
                """, Integer.class, userId, COURSE_ID)).isZero();

        TOTAL_TOOL_CALLS.addAndGet(answer.usage().toolCalls());
        TOTAL_MOCK_MODEL_CALLS.addAndGet(answer.usage().modelCalls());
        System.out.println("ASSISTANT_SCENARIO name=learning-suggestion tools=3 mockModelCalls=3 "
                + "deepSeekCalls=0 holdoutGenerationCases=0 progressRows=0");
    }

    @Test
    @Order(2)
    void acceptsAModelSelectedSearchThenOutlineOrder() {
        scenario = Scenario.SEARCH_THEN_OUTLINE;
        long userId = activeUserWithMaterialAccessAndNoProgress();

        GroundedAnswer answer;
        try (var run = guard.open(userId, "scenario-search-" + UUID.randomUUID(), identityCheck(userId))) {
            answer = agent.answer(userId, COURSE_ID, "先搜索同主题课程，再查看当前课程目录。", run);
        }

        assertThat(issuedTools).containsExactly("search_courses", "get_course_outline");
        assertThat(modelCalls).isEqualTo(3);
        assertThat(answer.trace()).containsExactlyElementsOf(issuedTools);
        assertThat(answer.modelMode()).isEqualTo("MOCK");
        assertThat(answer.insufficientEvidence()).isFalse();
        assertThat(answer.citations()).extracting(citation -> citation.kind())
                .contains("COURSE", "OUTLINE");
        assertThat(answer.usage().modelCalls()).isEqualTo(3);
        assertThat(answer.usage().toolCalls()).isEqualTo(2);
        assertThat(answer.usage().actualTotalTokens()).isEqualTo(60);
        assertThat(answer.usage().usageComplete()).isTrue();

        TOTAL_TOOL_CALLS.addAndGet(answer.usage().toolCalls());
        TOTAL_MOCK_MODEL_CALLS.addAndGet(answer.usage().modelCalls());
        System.out.println("ASSISTANT_SCENARIO name=search-then-outline tools=2 mockModelCalls=3 "
                + "deepSeekCalls=0 holdoutGenerationCases=0");
    }

    private ChatResponse scripted(ChatRequest request) throws Exception {
        modelRequests.add(request);
        modelCalls++;
        return switch (scenario) {
            case LEARNING_SUGGESTION -> learningSuggestionResponse(request);
            case SEARCH_THEN_OUTLINE -> searchThenOutlineResponse(request);
        };
    }

    private ChatResponse learningSuggestionResponse(ChatRequest request) throws Exception {
        if (issuedTools.isEmpty()) {
            return tools(new ToolCall("outline-1", "get_course_outline", scopedCourseArguments()));
        }
        if (issuedTools.size() == 1) {
            return tools(
                    new ToolCall("progress-1", "get_my_learning_progress", scopedCourseArguments()),
                    new ToolCall("materials-1", "search_course_materials", mapper.writeValueAsString(Map.of(
                            "courseId", Long.toString(COURSE_ID), "query", materialQuery, "limit", 3))));
        }
        Map<String, String> evidence = evidenceIds(request.messages());
        return finalAnswer(List.of(
                segment("已检索到与问题相关的课程材料。", List.of(required(evidence, "MATERIAL")), "FACT"),
                segment("本人在这门课尚无学习记录，建议先按课程目录顺序学习，并结合相关材料复习。",
                        List.of(required(evidence, "PROGRESS"), required(evidence, "OUTLINE")), "SUGGESTION")));
    }

    private ChatResponse searchThenOutlineResponse(ChatRequest request) throws Exception {
        if (issuedTools.isEmpty()) {
            return tools(new ToolCall("search-1", "search_courses",
                    mapper.writeValueAsString(Map.of("keyword", courseTitle, "limit", 3))));
        }
        if (issuedTools.size() == 1) {
            return tools(new ToolCall("outline-2", "get_course_outline", scopedCourseArguments()));
        }
        Map<String, String> evidence = evidenceIds(request.messages());
        return finalAnswer(List.of(segment("课程搜索结果与当前课程目录已经核验。",
                List.of(required(evidence, "COURSE"), required(evidence, "OUTLINE")), "FACT")));
    }

    private ChatResponse tools(ToolCall... calls) {
        issuedTools.addAll(java.util.Arrays.stream(calls).map(ToolCall::name).toList());
        return new ChatResponse("", List.of(calls), new Usage(10, 10, 20), "tool_calls", "MOCK");
    }

    private ChatResponse finalAnswer(List<Map<String, Object>> segments) throws Exception {
        String json = mapper.writeValueAsString(Map.of("insufficientEvidence", false, "answerSegments", segments));
        return new ChatResponse(json, List.of(), new Usage(10, 10, 20), "stop", "MOCK");
    }

    private Map<String, Object> segment(String text, List<String> ids, String kind) {
        Map<String, Object> segment = new LinkedHashMap<>();
        segment.put("text", text);
        segment.put("evidenceIds", ids);
        segment.put("kind", kind);
        return segment;
    }

    private String scopedCourseArguments() throws Exception {
        return mapper.writeValueAsString(Map.of("courseId", Long.toString(COURSE_ID)));
    }

    private Map<String, String> evidenceIds(List<ChatMessage> messages) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            if (!"tool".equals(message.role())) continue;
            JsonNode root = mapper.readTree(message.content());
            for (JsonNode observation : root.path("observations")) {
                result.putIfAbsent(observation.path("kind").asText(), observation.path("evidenceId").asText());
            }
        }
        return result;
    }

    private JsonNode evidence(String kind) {
        for (ChatRequest request : modelRequests) {
            for (ChatMessage message : request.messages()) {
                if (!"tool".equals(message.role())) continue;
                try {
                    for (JsonNode observation : mapper.readTree(message.content()).path("observations")) {
                        if (kind.equals(observation.path("kind").asText())) return observation;
                    }
                } catch (Exception error) {
                    throw new AssertionError("工具观察结果不是合法 JSON", error);
                }
            }
        }
        throw new AssertionError("没有找到类型为 " + kind + " 的工具证据");
    }

    private static String required(Map<String, String> evidence, String kind) {
        String id = evidence.get(kind);
        if (id == null || id.isBlank()) throw new AssertionError("缺少 " + kind + " 证据");
        return id;
    }

    private long activeUserWithMaterialAccessAndNoProgress() {
        List<Long> activeUsers = jdbc.queryForList("""
                SELECT id FROM user
                WHERE status = 'ACTIVE' AND deleted = 0
                ORDER BY id
                """, Long.class);
        for (Long userId : activeUsers) {
            List<Long> readable;
            try {
                readable = courses.readableLessonIds(userId, COURSE_ID);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!readable.contains(MATERIAL_LESSON_ID)) continue;
            Integer progressRows = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM learning_progress lp
                    JOIN course_lesson cl ON cl.id = lp.lesson_id
                    JOIN course_chapter cc ON cc.id = cl.chapter_id
                    WHERE lp.user_id = ? AND cc.course_id = ? AND cl.deleted = 0
                    """, Integer.class, userId, COURSE_ID);
            if (progressRows != null && progressRows == 0) return userId;
        }
        throw new AssertionError("真实数据库中没有可读取目标材料且在该课程尚无进度的 ACTIVE 用户");
    }

    private Runnable identityCheck(long userId) {
        return () -> assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM user WHERE id = ? AND status = 'ACTIVE' AND deleted = 0
                """, Integer.class, userId)).isOne();
    }

    private void clearScenarioRedis() {
        Set<String> keys = redis.keys(REDIS_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    enum Scenario { LEARNING_SUGGESTION, SEARCH_THEN_OUTLINE }

    @TestConfiguration
    static class ScenarioRedisConfiguration {
        @Bean
        @Primary
        AssistantRunStore scenarioAssistantRunStore(StringRedisTemplate redis) {
            return new RedisAssistantRunStore(redis, REDIS_PREFIX);
        }
    }
}
