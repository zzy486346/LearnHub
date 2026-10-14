package com.learnhub.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.AdminCourseLockMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.CourseChapter;
import com.learnhub.course.model.CourseLesson;
import com.learnhub.search.CourseIndexTaskService;
import com.learnhub.storage.MediaAssetMapper;
import com.learnhub.storage.MediaAssetService;
import com.learnhub.storage.MediaResponses;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class AdminCourseServiceTest {
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final AdminCourseLockMapper courseLockMapper = mock(AdminCourseLockMapper.class);
    private final CourseChapterMapper chapterMapper = mock(CourseChapterMapper.class);
    private final CourseLessonMapper lessonMapper = mock(CourseLessonMapper.class);
    private final CourseService courseService = mock(CourseService.class);
    private final CourseTaxonomyService taxonomyService = mock(CourseTaxonomyService.class);
    private final MediaAssetService mediaAssetService = mock(MediaAssetService.class);
    private final MediaAssetMapper mediaAssetMapper = mock(MediaAssetMapper.class);
    private final CourseIndexTaskService indexTaskService = mock(CourseIndexTaskService.class);
    private AdminCourseService service;

    @BeforeEach
    void setUp() {
        service = new AdminCourseService(courseMapper, courseLockMapper, chapterMapper, lessonMapper,
                courseService, taxonomyService, mediaAssetService, mediaAssetMapper, indexTaskService);
    }

    @Test
    void createsDraftCourseByDefault() {
        when(courseMapper.insert(any(Course.class))).thenAnswer(invocation -> {
            Course course = invocation.getArgument(0);
            course.setId(77L);
            assertThat(course.getStatus()).isEqualTo("DRAFT");
            return 1;
        });

        Long id = service.createCourse(new AdminCourseRequests.CreateCourse(
                1L, "  Spring Boot 进阶  ", "课程说明", "讲师", new BigDecimal("99.00"), null));

        assertThat(id).isEqualTo(77L);
        verify(indexTaskService).enqueue(77L);
    }

    @Test
    void publishingCompleteCourseEnqueuesIndexRefresh() {
        Course course = new Course();
        course.setId(88L);
        course.setCategoryId(1L);
        CourseChapter chapter = new CourseChapter();
        chapter.setId(188L);
        chapter.setCourseId(88L);
        CourseLesson lesson = new CourseLesson();
        lesson.setId(288L);
        lesson.setChapterId(188L);
        lesson.setDurationSeconds(60);
        lesson.setMediaUrl("https://media.example/lesson.mp4");
        when(courseLockMapper.selectForUpdate(88L)).thenReturn(course);
        when(courseMapper.selectById(88L)).thenReturn(course);
        when(chapterMapper.selectList(any())).thenReturn(java.util.List.of(chapter));
        when(lessonMapper.selectList(any())).thenReturn(java.util.List.of(lesson));

        service.publishCourse(88L);

        verify(courseMapper).update(any(), any());
        verify(indexTaskService).enqueue(88L);
    }

    @Test
    void listsCoursesWithFixedTenItemPageAndSafePageBoundary() {
        when(courseMapper.selectPage(any(Page.class), any(Wrapper.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Page<Course> result = service.list(0);

        ArgumentCaptor<Page<Course>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<Wrapper<Course>> queryCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(courseMapper).selectPage(pageCaptor.capture(), queryCaptor.capture());
        assertThat(result).isSameAs(pageCaptor.getValue());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(10);
        assertThat(queryCaptor.getValue()).isNotNull();
    }

    @Test
    void createsTrimmedLessonOnlyWhenChapterAndCourseExist() {
        CourseChapter chapter = new CourseChapter();
        chapter.setId(2001L);
        chapter.setCourseId(1001L);
        Course course = new Course();
        course.setId(1001L);
        when(chapterMapper.selectById(2001L)).thenReturn(chapter);
        when(courseLockMapper.selectForUpdate(1001L)).thenReturn(course);
        when(lessonMapper.insert(any(CourseLesson.class))).thenAnswer(invocation -> {
            CourseLesson lesson = invocation.getArgument(0);
            lesson.setId(3001L);
            assertThat(lesson.getTitle()).isEqualTo("第一课");
            return 1;
        });

        Long lessonId = service.createLesson(2001L,
                new AdminCourseRequests.CreateLesson("  第一课  ", 60, true, 1));

        assertThat(lessonId).isEqualTo(3001L);
    }

    @Test
    void rejectsLessonCreationForMissingParentsOrBlankTitle() {
        assertThatThrownBy(() -> service.createLesson(404L,
                new AdminCourseRequests.CreateLesson("第一课", 60, true, 1)))
                .isInstanceOf(BusinessException.class).hasMessage("章节不存在");

        CourseChapter orphan = new CourseChapter();
        orphan.setId(2001L);
        orphan.setCourseId(404L);
        when(chapterMapper.selectById(2001L)).thenReturn(orphan);
        assertThatThrownBy(() -> service.createLesson(2001L,
                new AdminCourseRequests.CreateLesson("第一课", 60, true, 1)))
                .isInstanceOf(BusinessException.class).hasMessage("课程不存在");

        CourseChapter chapter = new CourseChapter();
        chapter.setId(2002L);
        chapter.setCourseId(1001L);
        Course course = new Course();
        course.setId(1001L);
        when(chapterMapper.selectById(2002L)).thenReturn(chapter);
        when(courseLockMapper.selectForUpdate(1001L)).thenReturn(course);
        assertThatThrownBy(() -> service.createLesson(2002L,
                new AdminCourseRequests.CreateLesson("   ", 60, true, 1)))
                .isInstanceOf(BusinessException.class).hasMessage("课时标题不能为空");
    }

    @Test
    void logicallyDeletesLessonOnceAndRejectsRepeatedOrMissingDelete() {
        CourseLesson lesson = new CourseLesson();
        lesson.setId(3001L);
        lesson.setChapterId(2001L);
        CourseChapter chapter = new CourseChapter();
        chapter.setId(2001L);
        chapter.setCourseId(1001L);
        Course course = new Course();
        course.setId(1001L);
        course.setStatus("DRAFT");
        when(lessonMapper.selectById(3001L)).thenReturn(lesson).thenReturn(null);
        when(chapterMapper.selectById(2001L)).thenReturn(chapter);
        when(courseLockMapper.selectForUpdate(1001L)).thenReturn(course);

        service.deleteLesson(3001L);

        verify(lessonMapper).deleteById(3001L);
        assertThatThrownBy(() -> service.deleteLesson(3001L))
                .isInstanceOf(BusinessException.class).hasMessage("课时不存在");
        assertThatThrownBy(() -> service.deleteLesson(9999L))
                .isInstanceOf(BusinessException.class).hasMessage("课时不存在");
    }

    @Test
    void uploadsVideoAndBindsAssetToLesson() {
        CourseLesson lesson = new CourseLesson();
        lesson.setId(3001L);
        lesson.setChapterId(2001L);
        CourseChapter chapter = new CourseChapter();
        chapter.setId(2001L);
        chapter.setCourseId(1001L);
        Course course = new Course();
        course.setId(1001L);
        course.setStatus("DRAFT");
        when(lessonMapper.selectById(3001L)).thenReturn(lesson);
        when(chapterMapper.selectById(2001L)).thenReturn(chapter);
        when(courseLockMapper.selectForUpdate(1001L)).thenReturn(course);
        MockMultipartFile file = new MockMultipartFile(
                "file", "lesson.mp4", "video/mp4", new byte[] {1, 2, 3});
        MediaResponses.Asset asset = new MediaResponses.Asset(88L, "lesson.mp4", "video/mp4", 3,
                "READY", "COURSE_LESSON", 3001L, "https://signed.example/video", Instant.now(),
                LocalDateTime.now());
        when(mediaAssetService.uploadCourseVideo(9L, 3001L, file)).thenReturn(asset);

        assertThat(service.uploadLessonVideo(9L, 3001L, file).id()).isEqualTo(88L);
        verify(lessonMapper).update(any(), any());
    }

    @Test
    void rejectsImmediatePublishOnCourseCreation() {
        assertThatThrownBy(() -> service.createCourse(new AdminCourseRequests.CreateCourse(
                1L, "课程", null, "讲师", BigDecimal.ZERO, "PUBLISHED")))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_DRAFT_REQUIRED"));
    }

    @Test
    void rejectsPublishingCourseWithoutChapter() {
        Course course = new Course();
        course.setId(88L);
        course.setCategoryId(1L);
        when(courseLockMapper.selectForUpdate(88L)).thenReturn(course);
        when(courseMapper.selectById(88L)).thenReturn(course);
        when(chapterMapper.selectList(any())).thenReturn(java.util.List.of());

        assertThatThrownBy(() -> service.publishCourse(88L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_NOT_READY"));
    }

    @Test
    void rejectsAddingChapterToPublishedCourse() {
        Course course = new Course();
        course.setId(88L);
        course.setStatus("PUBLISHED");
        when(courseLockMapper.selectForUpdate(88L)).thenReturn(course);

        assertThatThrownBy(() -> service.createChapter(88L,
                new AdminCourseRequests.CreateChapter("追加章节", 2)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_OFFLINE_REQUIRED"));
    }

    @Test
    void rejectsPublishingWhenAnyChapterHasNoLesson() {
        Course course = new Course();
        course.setId(88L);
        course.setCategoryId(1L);
        CourseChapter first = new CourseChapter();
        first.setId(188L);
        first.setCourseId(88L);
        first.setTitle("基础章");
        CourseChapter empty = new CourseChapter();
        empty.setId(189L);
        empty.setCourseId(88L);
        empty.setTitle("空章节");
        CourseLesson lesson = new CourseLesson();
        lesson.setId(288L);
        lesson.setChapterId(188L);
        lesson.setDurationSeconds(60);
        lesson.setMediaUrl("https://media.example/lesson.mp4");
        when(courseLockMapper.selectForUpdate(88L)).thenReturn(course);
        when(courseMapper.selectById(88L)).thenReturn(course);
        when(chapterMapper.selectList(any())).thenReturn(java.util.List.of(first, empty));
        when(lessonMapper.selectList(any())).thenReturn(java.util.List.of(lesson));

        assertThatThrownBy(() -> service.publishCourse(88L))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("COURSE_NOT_READY");
                    assertThat(exception.getMessage()).contains("空章节");
                });
    }
}
