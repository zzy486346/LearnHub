package com.learnhub.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import org.junit.jupiter.api.Test;

class CourseServiceTest {
    private final CourseMapper mapper = mock(CourseMapper.class);
    private final CourseService service = new CourseService(mapper);

    @Test
    void returnsPublishedCourseDetail() {
        Course course = new Course();
        course.setId(1L);
        course.setTitle("Java 入门");
        when(mapper.selectOne(any())).thenReturn(course);

        assertThat(service.detail(1L).getTitle()).isEqualTo("Java 入门");
    }

    @Test
    void rejectsMissingOrUnpublishedCourse() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.detail(99L)).isInstanceOf(BusinessException.class);
    }
}
