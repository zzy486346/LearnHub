package com.learnhub.assistant.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.CurrentKnowledgeChunk;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.CourseChapter;
import com.learnhub.course.model.CourseLesson;
import com.learnhub.course.model.LearningProgress;
import com.learnhub.course.service.CourseService;
import com.learnhub.course.service.LearningProgressService;
import com.learnhub.search.CourseSearchService;
import com.learnhub.search.SearchResult;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AssistantToolsTest {
    private final CourseSearchService search = mock(CourseSearchService.class);
    private final CourseService courses = mock(CourseService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final LearningProgressService progress = mock(LearningProgressService.class);
    private final CourseChapterMapper chapters = mock(CourseChapterMapper.class);
    private final CourseLessonMapper lessons = mock(CourseLessonMapper.class);
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final AssistantRunGuard.Run run = mock(AssistantRunGuard.Run.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final AssistantTools tools = new AssistantTools(search, courses, retrieval, progress, chapters, lessons, mapper);
    private EvidenceBook book;

    @BeforeEach
    void setUp() {
        book = new EvidenceBook(7L, 10L, store, courses, mapper);
        when(courses.readableLessonIds(7L, 10L)).thenReturn(List.of(101L));
        when(chapters.selectList(any())).thenReturn(List.of());
        when(lessons.selectList(any())).thenReturn(List.of());
        when(progress.getByCourse(7L, 10L)).thenReturn(List.of());
    }

    @Test
    void definitionsExposeOnlyFourStrictReadOnlyTools() throws Exception {
        JsonNode definitions = mapper.valueToTree(tools.definitions());
        assertThat(definitions).hasSize(4);
        assertThat(definitions.toString()).contains("search_courses", "get_course_outline",
                "search_course_materials", "get_my_learning_progress", "additionalProperties\":false");
        assertThat(definitions.toString()).doesNotContain("userId", "role", "sql", "shell", "url", "command");
    }

    @Test
    void normalizedArgumentHashesAreStableAndNeverExecuteOrChargeTools() {
        String first = tools.normalizedArgumentsHash(new ToolCall("a", "search_courses",
                "{\"keyword\":\" Java \",\"limit\":3}"), 10);
        String second = tools.normalizedArgumentsHash(new ToolCall("b", "search_courses",
                "{\"limit\":3,\"keyword\":\"Java\"}"), 10);
        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
        org.mockito.Mockito.verifyNoInteractions(run, search, retrieval, progress, chapters, lessons, courses);
        assertThatThrownBy(() -> tools.normalizedArgumentsHash(new ToolCall("a", "get_my_learning_progress",
                "{\"courseId\":\"999\"}"), 10)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> tools.normalizedArgumentsHash(new ToolCall("a", "arbitrary", "{}"), 10))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void integralCourseIdsNormalizeToTheSameScopedStringWithoutWeakeningScopeChecks() {
        assertThat(tools.normalizedArgumentsHash(new ToolCall("a", "get_course_outline",
                "{\"courseId\":10}"), 10)).isEqualTo(tools.normalizedArgumentsHash(
                new ToolCall("b", "get_course_outline", "{\"courseId\":\"10\"}"), 10));
        for (String value : List.of("11", "10.0", "1e1", "0", "-10", "9223372036854775808", "true", "null")) {
            assertThatThrownBy(() -> tools.normalizedArgumentsHash(new ToolCall("bad", "get_course_outline",
                    "{\"courseId\":" + value + "}"), 10)).isInstanceOf(BusinessException.class);
        }
        org.mockito.Mockito.verifyNoInteractions(run, search, retrieval, progress, chapters, lessons, courses);
    }

    @Test
    void courseSearchOverfetchesThenUsesMysqlTruthAndRegistersEvidence() {
        when(search.search("Java", Set.of(), 6)).thenReturn(List.of(
                new SearchResult(20L, "过期索引标题", "", "旧讲师", "", Set.of(), 0, 1)));
        Course current = course(20L, "MySQL标题", "当前讲师");
        when(courses.requirePublished(20L)).thenReturn(current);

        Map<String, Object> result = tools.execute(7L, 10L,
                new ToolCall("call-1", "search_courses", "{\"keyword\":\" Java \",\"limit\":2}"), book, run);

        JsonNode data = observation(result, 0);
        assertThat(data.path("courseId").asText()).isEqualTo("20");
        assertThat(data.path("title").asText()).isEqualTo("MySQL标题");
        assertThat(data.path("instructor").asText()).isEqualTo("当前讲师");
        verify(run).toolCall("search_courses:{\"keyword\":\"Java\",\"limit\":2}");
        verify(search).search("Java", Set.of(), 6);
    }

    @Test
    void outlineIsLightweightShowsLockedTitlesAndInvalidatesOnChange() {
        CourseChapter chapter = chapter(1L, "第一章", 1);
        CourseLesson accessible = lesson(101L, 1L, "公开课时", 1, true);
        CourseLesson locked = lesson(102L, 1L, "锁定课时", 2, false);
        when(chapters.selectList(any())).thenReturn(List.of(chapter));
        when(lessons.selectList(any())).thenReturn(List.of(accessible, locked));

        Map<String, Object> result = tools.execute(7L, 10L,
                new ToolCall("call-2", "get_course_outline", "{\"courseId\":\"10\"}"), book, run);

        JsonNode data = observation(result, 0);
        assertThat(data.toString()).contains("公开课时", "锁定课时", "\"accessible\":false");
        assertThat(data.toString()).doesNotContain("mediaUrl", "mediaAssetId", "coverUrl");

        locked.setTitle("已改名");
        assertThatThrownBy(book::check).isInstanceOf(BusinessException.class).hasMessageContaining("已废弃");
    }

    @Test
    void materialsAreReturnedOnlyAsRegisteredMaterialObservations() {
        var source = new KnowledgeRetrievalService.Source("9", 1, "11", "10", "101",
                "Redis", "Lua", 0, "Lua保证脚本原子执行", 8.0);
        var chunk = new CurrentKnowledgeChunk(11, 9, 1, 10, 101, "Redis", 0, "Lua", "Lua保证脚本原子执行");
        when(retrieval.retrieve(7L, 10L, "Lua", 3)).thenReturn(
                new KnowledgeRetrievalService.Result("r", List.of(source), false, false));
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(chunk));

        Map<String, Object> result = tools.execute(7L, 10L,
                new ToolCall("call-3", "search_course_materials", "{\"courseId\":\"10\",\"query\":\"Lua\"}"), book, run);

        assertThat(observation(result, 0).path("text").asText()).contains("原子执行");
        assertThat(((List<?>) result.get("observations"))).singleElement().asString().contains("MATERIAL");
    }

    @Test
    void progressUsesServerIdentityFiltersUnreadableLessonsAndHidesInternalIds() {
        LearningProgress readable = progress(900L, 7L, 101L, 30, true);
        LearningProgress revoked = progress(901L, 7L, 102L, 40, false);
        when(progress.getByCourse(7L, 10L)).thenReturn(List.of(readable, revoked));

        Map<String, Object> result = tools.execute(7L, 10L,
                new ToolCall("call-4", "get_my_learning_progress", "{\"courseId\":\"10\"}"), book, run);

        String data = observation(result, 0).toString();
        assertThat(data).contains("\"lessonId\":\"101\"").doesNotContain("102", "900", "901", "userId");
        // 注册后立即执行一次 fresh callback，证明回给模型前的快照仍然有效。
        verify(progress, times(2)).getByCourse(7L, 10L);
    }

    @Test
    void progressSnapshotChangeInvalidatesEntireEvidenceBook() {
        LearningProgress row = progress(900L, 7L, 101L, 30, false);
        when(progress.getByCourse(7L, 10L)).thenReturn(List.of(row));
        tools.execute(7L, 10L,
                new ToolCall("call-5", "get_my_learning_progress", "{\"courseId\":\"10\"}"), book, run);

        row.setCompleted(true);
        assertThatThrownBy(book::check).isInstanceOf(BusinessException.class).hasMessageContaining("已废弃");
    }

    @Test
    void rejectsUnknownIdentityInvalidJsonAndCrossScopeBeforeBusinessCalls() {
        List<ToolCall> invalid = List.of(
                new ToolCall("x1", "delete_progress", "{}"),
                new ToolCall("x2", "search_courses", "{\"keyword\":\"Java\",\"userId\":99}"),
                new ToolCall("x3", "search_courses", "{\"keyword\":\"a\",\"keyword\":\"b\"}"),
                new ToolCall("x4", "search_courses", "{\"keyword\":\"a\"} {}"),
                new ToolCall("x5", "get_course_outline", "{\"courseId\":10.5}"),
                new ToolCall("x6", "get_course_outline", "{\"courseId\":\"11\"}"),
                new ToolCall("x7", "search_course_materials", "{\"courseId\":\"10\",\"query\":\"\"}"));

        invalid.forEach(call -> assertThatThrownBy(() -> tools.execute(7L, 10L, call, book, run))
                .isInstanceOf(BusinessException.class).hasMessageContaining("未通过校验"));
        verify(search, never()).search(anyString(), any(), anyInt());
        verify(retrieval, never()).retrieve(any(), any(Long.class), anyString(), anyInt());
        verify(progress, never()).getByCourse(any(), any());
    }

    @Test
    void omittedAndExplicitDefaultLimitNormalizeToSameDuplicateSignature() {
        Set<String> signatures = new HashSet<>();
        doAnswer(invocation -> {
            if (!signatures.add(invocation.getArgument(0))) {
                throw new BusinessException("ASSISTANT_TOOL_LOOP", "重复调用");
            }
            return null;
        }).when(run).toolCall(anyString());
        when(search.search(eq("Java"), eq(Set.of()), eq(9))).thenReturn(List.of());

        tools.execute(7L, 10L, new ToolCall("a", "search_courses", "{\"keyword\":\"Java\"}"), book, run);
        assertThatThrownBy(() -> tools.execute(7L, 10L,
                new ToolCall("b", "search_courses", "{\"limit\":3,\"keyword\":\" Java \"}"), book, run))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重复调用");
        verify(search).search("Java", Set.of(), 9);
    }

    @SuppressWarnings("unchecked")
    private static JsonNode observation(Map<String, Object> result, int index) {
        Map<String, Object> observation = (Map<String, Object>) ((List<?>) result.get("observations")).get(index);
        return (JsonNode) observation.get("data");
    }

    private static Course course(long id, String title, String instructor) {
        Course value = new Course();
        value.setId(id); value.setTitle(title); value.setInstructor(instructor); value.setStatus("PUBLISHED");
        return value;
    }

    private static CourseChapter chapter(long id, String title, int order) {
        CourseChapter value = new CourseChapter();
        value.setId(id); value.setCourseId(10L); value.setTitle(title); value.setSortOrder(order);
        return value;
    }

    private static CourseLesson lesson(long id, long chapterId, String title, int order, boolean preview) {
        CourseLesson value = new CourseLesson();
        value.setId(id); value.setChapterId(chapterId); value.setTitle(title); value.setSortOrder(order);
        value.setDurationSeconds(60); value.setFreePreview(preview);
        value.setMediaUrl("https://secret.invalid/video"); value.setMediaAssetId(999L);
        return value;
    }

    private static LearningProgress progress(long id, long userId, long lessonId, int position, boolean completed) {
        LearningProgress value = new LearningProgress();
        value.setId(id); value.setUserId(userId); value.setLessonId(lessonId); value.setPositionSeconds(position);
        value.setCompleted(completed); value.setLastLearnedAt(LocalDateTime.of(2026, 10, 17, 9, 0));
        return value;
    }
}
