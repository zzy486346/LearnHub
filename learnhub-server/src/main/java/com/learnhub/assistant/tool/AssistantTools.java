package com.learnhub.assistant.tool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** 学习助理可调用的只读工具。身份和课程范围始终由服务端传入。 */
@Component
public class AssistantTools {
    private static final Set<String> NAMES = Set.of(
            "search_courses", "get_course_outline", "search_course_materials", "get_my_learning_progress");

    private final CourseSearchService search;
    private final CourseService courses;
    private final KnowledgeRetrievalService retrieval;
    private final LearningProgressService progress;
    private final CourseChapterMapper chapters;
    private final CourseLessonMapper lessons;
    private final ObjectMapper mapper;

    public AssistantTools(CourseSearchService search, CourseService courses, KnowledgeRetrievalService retrieval,
                          LearningProgressService progress, CourseChapterMapper chapters,
                          CourseLessonMapper lessons, ObjectMapper mapper) {
        this.search = search;
        this.courses = courses;
        this.retrieval = retrieval;
        this.progress = progress;
        this.chapters = chapters;
        this.lessons = lessons;
        this.mapper = mapper;
    }

    public List<Map<String, Object>> definitions() {
        return List.of(
                definition("search_courses", "搜索公开且已上架的课程", Map.of(
                        "keyword", stringSchema("搜索词，1至200字"),
                        "limit", integerSchema(1, 5, 3)), List.of("keyword")),
                definition("get_course_outline", "读取当前课程的轻量目录，不含媒体地址", Map.of(
                        "courseId", stringSchema("当前课程的正整数编号字符串")), List.of("courseId")),
                definition("search_course_materials", "在当前课程可读讲解稿中检索材料", Map.of(
                        "courseId", stringSchema("当前课程的正整数编号字符串"),
                        "query", stringSchema("材料问题，1至500字"),
                        "limit", integerSchema(1, 5, 3)), List.of("courseId", "query")),
                definition("get_my_learning_progress", "读取登录用户在当前课程的学习进度", Map.of(
                        "courseId", stringSchema("当前课程的正整数编号字符串")), List.of("courseId")));
    }

    public Map<String, Object> execute(Long userId, long scopedCourseId, ToolCall call,
                                       EvidenceBook book, AssistantRunGuard.Run run) {
        if (userId == null || scopedCourseId <= 0 || call == null || book == null || run == null) throw invalid();
        run.check();
        if (!NAMES.contains(call.name())) throw invalid();
        Map<String, Object> arguments = normalize(call, scopedCourseId);
        run.toolCall(call.name() + ":" + json(new TreeMap<>(arguments)));
        book.check();

        List<String> evidenceIds = switch (call.name()) {
            case "search_courses" -> searchCourses(arguments, book);
            case "get_course_outline" -> List.of(outline(userId, scopedCourseId, book));
            case "search_course_materials" -> materials(userId, scopedCourseId, arguments, book);
            case "get_my_learning_progress" -> List.of(learningProgress(userId, scopedCourseId, book));
            default -> throw invalid();
        };

        book.check();
        run.check();
        return Map.of("toolCallId", call.id(), "tool", call.name(), "observations",
                evidenceIds.stream().map(book::observation).toList());
    }

    private List<String> searchCourses(Map<String, Object> arguments, EvidenceBook book) {
        String keyword = (String) arguments.get("keyword");
        int limit = (Integer) arguments.get("limit");
        List<String> ids = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        for (var candidate : search.search(keyword, Set.of(), limit * 3)) {
            if (!seen.add(candidate.id())) continue;
            Course course = courses.requirePublished(candidate.id());
            JsonNode snapshot = courseSnapshot(course);
            ids.add(book.register("course:" + course.getId(), "COURSE", snapshot,
                    () -> courseSnapshot(courses.requirePublished(course.getId()))));
            if (ids.size() == limit) break;
        }
        return ids;
    }

    private String outline(Long userId, long courseId, EvidenceBook book) {
        JsonNode snapshot = outlineSnapshot(userId, courseId);
        return book.register("outline:" + courseId, "OUTLINE", snapshot,
                () -> outlineSnapshot(userId, courseId));
    }

    private List<String> materials(Long userId, long courseId, Map<String, Object> arguments, EvidenceBook book) {
        var result = retrieval.retrieve(userId, courseId, (String) arguments.get("query"),
                (Integer) arguments.get("limit"));
        return result.sources().stream().map(book::material).toList();
    }

    private String learningProgress(Long userId, long courseId, EvidenceBook book) {
        JsonNode snapshot = progressSnapshot(userId, courseId);
        return book.register("progress:" + courseId, "PROGRESS", snapshot,
                () -> progressSnapshot(userId, courseId));
    }

    private JsonNode courseSnapshot(Course course) {
        return mapper.valueToTree(Map.of(
                "courseId", Long.toString(course.getId()),
                "title", safe(course.getTitle()),
                "instructor", safe(course.getInstructor())));
    }

    private JsonNode outlineSnapshot(Long userId, long courseId) {
        courses.requireAvailable(courseId, userId);
        Set<Long> readable = Set.copyOf(courses.readableLessonIds(userId, courseId));
        List<CourseChapter> chapterRows = chapters.selectList(new LambdaQueryWrapper<CourseChapter>()
                .eq(CourseChapter::getCourseId, courseId)
                .orderByAsc(CourseChapter::getSortOrder).orderByAsc(CourseChapter::getId)).stream()
                .sorted(Comparator.comparing(CourseChapter::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(CourseChapter::getId)).toList();
        List<Long> chapterIds = chapterRows.stream().map(CourseChapter::getId).toList();
        Map<Long, List<CourseLesson>> byChapter = new LinkedHashMap<>();
        if (!chapterIds.isEmpty()) {
            lessons.selectList(new LambdaQueryWrapper<CourseLesson>().in(CourseLesson::getChapterId, chapterIds)
                            .orderByAsc(CourseLesson::getSortOrder).orderByAsc(CourseLesson::getId)).stream()
                    .sorted(Comparator.comparing(CourseLesson::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                            .thenComparing(CourseLesson::getId))
                    .forEach(lesson -> byChapter.computeIfAbsent(lesson.getChapterId(), ignored -> new ArrayList<>())
                            .add(lesson));
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (CourseChapter chapter : chapterRows) {
            List<Map<String, Object>> lessonItems = byChapter.getOrDefault(chapter.getId(), List.of()).stream()
                    .map(lesson -> mapOf(
                            "lessonId", Long.toString(lesson.getId()),
                            "title", safe(lesson.getTitle()),
                            "order", lesson.getSortOrder(),
                            "durationSeconds", lesson.getDurationSeconds(),
                            "accessible", readable.contains(lesson.getId())))
                    .toList();
            items.add(mapOf("chapterId", Long.toString(chapter.getId()), "title", safe(chapter.getTitle()),
                    "order", chapter.getSortOrder(), "lessons", lessonItems));
        }
        return mapper.valueToTree(Map.of("courseId", Long.toString(courseId), "chapters", items));
    }

    private JsonNode progressSnapshot(Long userId, long courseId) {
        List<LearningProgress> rows = progress.getByCourse(userId, courseId);
        Set<Long> readable = Set.copyOf(courses.readableLessonIds(userId, courseId));
        List<Map<String, Object>> items = rows.stream().filter(row -> readable.contains(row.getLessonId()))
                .map(row -> mapOf(
                        "lessonId", Long.toString(row.getLessonId()),
                        "positionSeconds", row.getPositionSeconds(),
                        "completed", Boolean.TRUE.equals(row.getCompleted()),
                        "lastLearnedAt", row.getLastLearnedAt() == null ? null : row.getLastLearnedAt().toString()))
                .toList();
        return mapper.valueToTree(Map.of("courseId", Long.toString(courseId), "progress", items));
    }

    private Map<String, Object> normalize(ToolCall call, long scopedCourseId) {
        JsonNode root;
        try {
            root = mapper.reader().with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(call.arguments());
        } catch (Exception error) {
            throw invalid();
        }
        return switch (call.name()) {
            case "search_courses" -> {
                fields(root, Set.of("keyword", "limit"), Set.of("keyword"));
                yield mapOf("keyword", text(root, "keyword", 200), "limit", limit(root));
            }
            case "get_course_outline", "get_my_learning_progress" -> {
                fields(root, Set.of("courseId"), Set.of("courseId"));
                yield Map.of("courseId", Long.toString(scoped(root, scopedCourseId)));
            }
            case "search_course_materials" -> {
                fields(root, Set.of("courseId", "query", "limit"), Set.of("courseId", "query"));
                long courseId = scoped(root, scopedCourseId);
                yield mapOf("courseId", Long.toString(courseId), "query", text(root, "query", 500),
                        "limit", limit(root));
            }
            default -> throw invalid();
        };
    }

    private static void fields(JsonNode root, Set<String> allowed, Set<String> required) {
        if (root == null || !root.isObject()) throw invalid();
        root.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw invalid(); });
        if (!required.stream().allMatch(root::has)) throw invalid();
    }

    private static String text(JsonNode root, String name, int max) {
        JsonNode value = root.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        String normalized = value.asText().trim();
        if (normalized.isEmpty() || normalized.length() > max) throw invalid();
        return normalized;
    }

    private static int limit(JsonNode root) {
        JsonNode value = root.get("limit");
        if (value == null) return 3;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
        int limit = value.intValue();
        if (limit < 1 || limit > 5) throw invalid();
        return limit;
    }

    private static long scoped(JsonNode root, long scopedCourseId) {
        JsonNode value = root.get("courseId");
        if (value == null || !value.isTextual() || !value.asText().matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            long courseId = Long.parseLong(value.asText());
            if (courseId != scopedCourseId) throw invalid();
            return courseId;
        } catch (NumberFormatException error) {
            throw invalid();
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw invalid();
        }
    }

    private static Map<String, Object> definition(String name, String description,
                                                   Map<String, Object> properties, List<String> required) {
        return Map.of("type", "function", "function", Map.of(
                "name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", properties,
                        "required", required, "additionalProperties", false)));
    }

    private static Map<String, Object> stringSchema(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integerSchema(int minimum, int maximum, int defaultValue) {
        return Map.of("type", "integer", "minimum", minimum, "maximum", maximum, "default", defaultValue);
    }

    private static Map<String, Object> mapOf(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static BusinessException invalid() {
        return new BusinessException("ASSISTANT_TOOL_INVALID", "工具名称或参数未通过校验");
    }
}
