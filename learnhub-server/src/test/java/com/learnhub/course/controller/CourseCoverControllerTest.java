package com.learnhub.course.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.storage.MediaAssetService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class CourseCoverControllerTest {
    private final CourseMapper courseMapper = mock(CourseMapper.class);
    private final MediaAssetService mediaAssetService = mock(MediaAssetService.class);
    private final CourseCoverController controller = new CourseCoverController(courseMapper, mediaAssetService);

    @Test
    void redirectsStableCoverPathToFreshAssetUrl() {
        Course course = new Course();
        course.setId(1001L);
        course.setCoverMediaId(88L);
        when(courseMapper.selectById(1001L)).thenReturn(course);
        when(mediaAssetService.readyAccessUrl(88L)).thenReturn("https://signed.example/cover?fresh=1");

        var response = controller.cover(1001L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://signed.example/cover?fresh=1");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void rejectsMissingOrUnreadyCover() {
        Course course = new Course();
        course.setId(1001L);
        course.setCoverMediaId(88L);
        when(courseMapper.selectById(1001L)).thenReturn(course);

        assertThatThrownBy(() -> controller.cover(1001L))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("COURSE_COVER_NOT_READY"));
    }
}
