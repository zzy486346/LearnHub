package com.learnhub.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.mapper.CourseChapterMapper;
import com.learnhub.course.mapper.CourseLessonMapper;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.course.model.CourseLesson;
import com.learnhub.storage.MediaAssetService;
import com.learnhub.storage.MediaResponses;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AdminCourseServiceTest {
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final CourseChapterMapper chapterMapper = mock(CourseChapterMapper.class);
    private final CourseLessonMapper lessonMapper = mock(CourseLessonMapper.class);
    private final CourseService courseService = mock(CourseService.class);
    private final MediaAssetService mediaAssetService = mock(MediaAssetService.class);
    private AdminCourseService service;

    @BeforeEach
    void setUp() {
        service = new AdminCourseService(courseMapper, chapterMapper, lessonMapper,
                courseService, mediaAssetService);
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
    }

    @Test
    void uploadsVideoAndBindsAssetToLesson() {
        CourseLesson lesson = new CourseLesson();
        lesson.setId(3001L);
        when(lessonMapper.selectById(3001L)).thenReturn(lesson);
        MockMultipartFile file = new MockMultipartFile(
                "file", "lesson.mp4", "video/mp4", new byte[] {1, 2, 3});
        MediaResponses.Asset asset = new MediaResponses.Asset(88L, "lesson.mp4", "video/mp4", 3,
                "READY", "COURSE_LESSON", 3001L, "https://signed.example/video", Instant.now(),
                LocalDateTime.now());
        when(mediaAssetService.uploadCourseVideo(9L, 3001L, file)).thenReturn(asset);

        assertThat(service.uploadLessonVideo(9L, 3001L, file).id()).isEqualTo(88L);
        verify(lessonMapper).update(any(), any());
    }
}
