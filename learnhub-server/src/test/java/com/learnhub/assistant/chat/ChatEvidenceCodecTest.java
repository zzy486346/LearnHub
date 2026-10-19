package com.learnhub.assistant.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.learnhub.assistant.chat.application.ChatEvidenceCodec;
import com.learnhub.assistant.chat.application.ChatHistoryService;
import com.learnhub.assistant.chat.application.ChatPersistenceService;
import com.learnhub.assistant.knowledge.CurrentKnowledgeChunk;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.assistant.model.ToolCall;
import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.assistant.tool.AssistantTools;
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
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatEvidenceCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final KnowledgeStore store = mock(KnowledgeStore.class);
    private final CourseService courses = mock(CourseService.class);
    private final KnowledgeRetrievalService retrieval = mock(KnowledgeRetrievalService.class);
    private final LearningProgressService progress = mock(LearningProgressService.class);
    private final CourseSearchService search = mock(CourseSearchService.class);
    private final CourseChapterMapper chapters = mock(CourseChapterMapper.class);
    private final CourseLessonMapper lessons = mock(CourseLessonMapper.class);
    private final AssistantRunGuard.Run run = mock(AssistantRunGuard.Run.class);
    private final AssistantTools tools = new AssistantTools(search, courses, retrieval, progress, chapters, lessons, mapper);
    private final ChatEvidenceCodec codec = new ChatEvidenceCodec(tools);
    private final KnowledgeRetrievalService.Source material = new KnowledgeRetrievalService.Source(
            "9", 1, "11", "10", "101", "课程材料", "原子性", 0, "原子操作讲义", 1);
    private CourseChapter chapter;
    private CourseLesson lesson;
    private Course publicCourse;
    private LearningProgress learned;
    private ChatHistoryService history;

    @BeforeEach
    void fixture() {
        chapter = new CourseChapter(); chapter.setId(1L); chapter.setCourseId(10L);
        chapter.setTitle("第一章"); chapter.setSortOrder(1);
        lesson = new CourseLesson(); lesson.setId(101L); lesson.setChapterId(1L);
        lesson.setTitle("原子性"); lesson.setSortOrder(1); lesson.setDurationSeconds(300);
        publicCourse = new Course(); publicCourse.setId(20L); publicCourse.setTitle("公开进阶课程");
        publicCourse.setInstructor("讲师");
        learned = learned(7, 30);
        when(courses.readableLessonIds(7L, 10L)).thenReturn(List.of(101L));
        when(courses.readableLessonIds(8L, 10L)).thenReturn(List.of(101L));
        when(chapters.selectList(any())).thenAnswer(call -> List.of(chapter));
        when(lessons.selectList(any())).thenAnswer(call -> List.of(lesson));
        when(progress.getByCourse(7L, 10L)).thenAnswer(call -> List.of(learned));
        when(progress.getByCourse(8L, 10L)).thenReturn(List.of(learned(8, 99)));
        when(search.search("课程", Set.of(), 3)).thenReturn(List.of(
                new SearchResult(20L, "公开进阶课程", "", "讲师", "", Set.of(), 0, 1)));
        when(courses.requirePublished(20L)).thenAnswer(call -> publicCourse);
        when(store.currentChunks(List.of(11L))).thenReturn(List.of(
                new CurrentKnowledgeChunk(11, 9, 1, 10, 101, "课程材料", 0, "原子性", "原子操作讲义")));
        var qa = mock(CourseQaService.class);
        when(qa.evidenceBook(anyLong(), eq(10L))).thenAnswer(call -> book(call.getArgument(0)));
        history = new ChatHistoryService(mock(ChatPersistenceService.class), courses, qa, mapper, codec);
    }

    @Test
    void legacyArrayStillRestoresMaterialAndChecksCurrentPermissions() throws Exception {
        var restored = book(7);
        assertThat(codec.restoreInto(7, 10, mapper.writeValueAsString(List.of(material)), restored))
                .containsExactly(material);
        assertThat(restored.citation("E1").chunkId()).isEqualTo("11");
        when(courses.readableLessonIds(7L, 10L)).thenReturn(List.of());
        assertThatThrownBy(restored::check).isInstanceOf(BusinessException.class);
    }

    @Test
    void nonemptyStructuredDependenciesRoundTripInExactEvidenceOrderWithoutToolExecution() {
        var original = original();
        String encoded = codec.encode(original);
        assertThat(encoded).contains("\"version\":1", "\"positionSeconds\":30", "\"chapters\":[{")
                .doesNotContain("userId", "supplier", "current", "toolCallId");
        clearInvocations(run, search);
        var restored = book(7);
        assertThat(codec.restoreInto(7, 10, encoded, restored)).containsExactly(material);
        assertThat(restored.exportDependencies()).extracting(EvidenceBook.Dependency::kind)
                .containsExactly("OUTLINE", "MATERIAL", "PROGRESS", "COURSE");
        assertThat(restored.citation("E2").chunkId()).isEqualTo("11");
        assertThat(ChatEvidenceCodec.parse(encoded)).extracting(ChatEvidenceCodec.ParsedEntry::evidenceId)
                .containsExactly("E1", "E2", "E3", "E4");
        assertThat(codec.encode(restored)).isEqualTo(encoded);
        verifyNoInteractions(run, search);
        assertThat(history.verify(7, 10, message(encoded)).restricted()).isFalse();
    }

    @Test
    void exportedSnapshotsCannotMutateBookOrLeakSuppliers() {
        var original = original();
        var exported = original.exportDependencies();
        ((ObjectNode) exported.get(0).snapshot()).put("courseId", "999");
        assertThatCode(original::check).doesNotThrowAnyException();
        assertThat(codec.encode(original)).doesNotContain("999");
    }

    @Test
    void progressChangeHidesWholeHistoricalAnswerAndAllSources() {
        String encoded = codec.encode(original());
        learned.setPositionSeconds(99);
        assertHidden(encoded);
    }

    @Test
    void outlineChangeHidesWholeHistoricalAnswerAndAllSources() {
        String encoded = codec.encode(original());
        lesson.setTitle("修改后的目录标题");
        assertHidden(encoded);
    }

    @Test
    void permissionRevocationHidesWholeHistoricalAnswerAndAllSources() {
        String encoded = codec.encode(original());
        when(courses.readableLessonIds(7L, 10L)).thenReturn(List.of());
        assertHidden(encoded);
    }

    @Test
    void unpublishedPublicCourseHidesWholeHistoricalAnswerAndAllSources() {
        String encoded = codec.encode(original());
        when(courses.requirePublished(20L)).thenThrow(new BusinessException("课程未上架"));
        assertHidden(encoded);
    }

    @Test
    void foreignUsersProgressIsNeverReusedAndCurrentOwnerIsBoundServerSide() {
        String encoded = codec.encode(original());
        clearInvocations(progress);
        assertThatThrownBy(() -> codec.restoreInto(8, 10, encoded, book(8)))
                .isInstanceOf(BusinessException.class);
        verify(progress).getByCourse(8L, 10L);
        verify(progress, never()).getByCourse(7L, 10L);
        when(progress.getByCourse(8L, 10L)).thenReturn(List.of(learned(8, 30)));
        var restored = book(8);
        assertThatCode(() -> codec.restoreInto(8, 10, encoded, restored)).doesNotThrowAnyException();
        clearInvocations(progress);
        restored.check();
        verify(progress).getByCourse(8L, 10L);
        verify(progress, never()).getByCourse(7L, 10L);
    }

    @Test
    void anotherCourseCannotRestoreScopedOutlineOrProgress() {
        String encoded = codec.encode(original());
        clearInvocations(progress, chapters);
        assertThatThrownBy(() -> codec.restoreInto(7, 30, encoded,
                new EvidenceBook(7L, 30, store, courses, mapper))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(progress, chapters);
    }

    @Test
    void malformedUnknownDuplicateTrailingAndUnsupportedFormatsFailClosed() throws Exception {
        String valid = codec.encode(original());
        var unknownRoot = (ObjectNode) mapper.readTree(valid); unknownRoot.put("userId", 7);
        var unknownEntry = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) unknownEntry.withArray("entries").get(0)).put("supplier", "arbitrary");
        var wrongKey = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) wrongKey.withArray("entries").get(0)).put("key", "outline:999");
        var unknownSnapshot = (ObjectNode) mapper.readTree(valid);
        ((ObjectNode) unknownSnapshot.withArray("entries").get(2).get("snapshot")).put("userId", 999);
        for (String invalid : List.of("not-json", "null", "{}", "{\"version\":2,\"entries\":[]}",
                "{\"version\":1,\"version\":1,\"entries\":[]}", valid + " {}", unknownRoot.toString(),
                unknownEntry.toString(), wrongKey.toString(), unknownSnapshot.toString(), " ".repeat(100_001))) {
            assertThatThrownBy(() -> ChatEvidenceCodec.parse(invalid)).isInstanceOf(BusinessException.class);
            assertThat(history.verify(7, 10, message(invalid)).restricted()).isTrue();
        }
        var tooMany = mapper.createObjectNode().put("version", 1);
        var array = tooMany.putArray("entries");
        for (int i = 0; i < 21; i++) array.add(mapper.readTree(valid).get("entries").get(0));
        assertThatThrownBy(() -> ChatEvidenceCodec.parse(tooMany.toString())).isInstanceOf(BusinessException.class);
    }

    private EvidenceBook original() {
        var original = book(7);
        tools.execute(7L, 10, new ToolCall("outline", "get_course_outline", "{\"courseId\":\"10\"}"), original, run);
        original.material(material);
        tools.execute(7L, 10, new ToolCall("progress", "get_my_learning_progress", "{\"courseId\":\"10\"}"), original, run);
        tools.execute(7L, 10, new ToolCall("course", "search_courses", "{\"keyword\":\"课程\",\"limit\":1}"), original, run);
        return original;
    }

    private EvidenceBook book(long owner) { return new EvidenceBook(owner, 10, store, courses, mapper); }
    private void assertHidden(String encoded) {
        var result = history.verify(7, 10, message(encoded));
        assertThat(result.restricted()).isTrue();
        assertThat(result.content()).isEmpty();
        assertThat(result.sources()).isEmpty();
    }
    private static ChatPersistenceService.Message message(String encoded) {
        return new ChatPersistenceService.Message("m", 2, "ASSISTANT", "完整历史回答", "COMPLETED", "run", encoded);
    }
    private static LearningProgress learned(long owner, int position) {
        var result = new LearningProgress(); result.setId(1000 + owner); result.setUserId(owner);
        result.setLessonId(101L); result.setPositionSeconds(position); result.setCompleted(false);
        result.setLastLearnedAt(LocalDateTime.of(2026, 10, 19, 10, 0));
        return result;
    }
}
