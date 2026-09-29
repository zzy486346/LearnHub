package com.learnhub.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.dto.CourseDetailResponse;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.CourseChapter;
import com.learnhub.course.model.CourseLesson;
import com.learnhub.storage.MediaAssetService;
import java.util.List;
import org.junit.jupiter.api.Test;

class CourseServiceTest {
    private final CourseMapper mapper = mock(CourseMapper.class);
    private final CourseChapterMapper chapterMapper = mock(CourseChapterMapper.class);
    private final CourseLessonMapper lessonMapper = mock(CourseLessonMapper.class);
    private final MediaAssetService mediaAssetService = mock(MediaAssetService.class);
    private final CourseService service = new CourseService(mapper, chapterMapper, lessonMapper, mediaAssetService);

    @Test
    void returnsPublishedCourseDetailWithSortedChaptersAndLessons() {
        Course course = new Course();
        course.setId(1L);
        course.setTitle("Java 入门");
        when(mapper.selectOne(any())).thenReturn(course);
        CourseChapter firstChapter = chapter(11L, 1L, "基础", 1);
        CourseChapter secondChapter = chapter(12L, 1L, "进阶", 2);
        when(chapterMapper.selectList(any())).thenReturn(List.of(secondChapter, firstChapter));
        CourseLesson firstLesson = lesson(21L, 11L, "准备环境", 1);
        CourseLesson secondLesson = lesson(22L, 11L, "创建项目", 2);
        CourseLesson advancedLesson = lesson(23L, 12L, "数据访问", 1);
        advancedLesson.setMediaAssetId(88L);
        when(mediaAssetService.readyAccessUrl(88L)).thenReturn("https://signed.example/course.mp4");
        when(lessonMapper.selectList(any())).thenReturn(List.of(secondLesson, advancedLesson, firstLesson));

        CourseDetailResponse detail = service.detail(1L);

        assertThat(detail.title()).isEqualTo("Java 入门");
        assertThat(detail.chapters()).extracting(chapter -> chapter.title())
                .containsExactly("基础", "进阶");
        assertThat(detail.chapters().get(0).lessons()).extracting(lesson -> lesson.title())
                .containsExactly("准备环境", "创建项目");
        assertThat(detail.chapters().get(1).lessons()).extracting(lesson -> lesson.title())
                .containsExactly("数据访问");
        assertThat(detail.chapters().get(1).lessons().get(0).mediaUrl())
                .isEqualTo("https://signed.example/course.mp4");
    }

    @Test
    void returnsEmptyChapterListWhenCourseHasNoCurriculum() {
        Course course = new Course();
        course.setId(1L);
        when(mapper.selectOne(any())).thenReturn(course);
        when(chapterMapper.selectList(any())).thenReturn(List.of());

        CourseDetailResponse detail = service.detail(1L);

        assertThat(detail.chapters()).isEmpty();
    }

    @Test
    void rejectsMissingOrUnpublishedCourse() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.detail(99L)).isInstanceOf(BusinessException.class);
    }

    private CourseChapter chapter(Long id, Long courseId, String title, int sortOrder) {
        CourseChapter chapter = new CourseChapter();
        chapter.setId(id);
        chapter.setCourseId(courseId);
        chapter.setTitle(title);
        chapter.setSortOrder(sortOrder);
        return chapter;
    }

    private CourseLesson lesson(Long id, Long chapterId, String title, int sortOrder) {
        CourseLesson lesson = new CourseLesson();
        lesson.setId(id);
        lesson.setChapterId(chapterId);
        lesson.setTitle(title);
        lesson.setSortOrder(sortOrder);
        lesson.setDurationSeconds(60);
        lesson.setFreePreview(false);
        return lesson;
    }
}
