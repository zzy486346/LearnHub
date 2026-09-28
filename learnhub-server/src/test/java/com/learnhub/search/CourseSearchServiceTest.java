package com.learnhub.search;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.course.model.Course;
import com.learnhub.course.service.CourseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseSearchServiceTest {
    @Test
    void searchesDatabaseCoursesByInstructorWhenElasticsearchIsEmpty() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CourseSearchRepository> repositoryProvider = mock(ObjectProvider.class);
        CourseSearchRepository repository = mock(CourseSearchRepository.class);
        CourseService courseService = mock(CourseService.class);
        Course course = new Course();
        course.setId(1002L);
        course.setTitle("大模型应用开发入门");
        course.setDescription("面向开发者的大模型应用课程。");
        course.setInstructor("周老师");
        course.setLikeCount(0L);
        Page<Course> page = Page.of(1, 100);
        page.setRecords(List.of(course));
        when(repositoryProvider.getIfAvailable()).thenReturn(repository);
        when(repository.findAll()).thenReturn(List.of());
        when(courseService.list(1, 100, null, null)).thenReturn(page);
        CourseSearchService service = new CourseSearchService(repositoryProvider, courseService);

        var results = service.search("周老师", Set.of(), 10);

        assertEquals(1, results.size());
        assertEquals(1002L, results.get(0).id());
        assertEquals("周老师", results.get(0).instructor());
    }

    @Test
    void fallsBackToMemoryAndRanksLikesAsBusinessWeight() {
        @SuppressWarnings("unchecked")
        ObjectProvider<CourseSearchRepository> repositoryProvider = mock(ObjectProvider.class);
        CourseSearchService service = new CourseSearchService(repositoryProvider);
        service.index(new CourseSearchDocument(1L, "Java 并发", "线程与锁", Set.of("Java"), 10));
        service.index(new CourseSearchDocument(2L, "Java 基础", "语法入门", Set.of("Java"), 100));

        var results = service.search("Java", Set.of("Java"), 10);

        assertEquals(2L, results.get(0).id());
        assertEquals("Java 并发", service.suggest("Java 并", 10).get(0));
    }
}
