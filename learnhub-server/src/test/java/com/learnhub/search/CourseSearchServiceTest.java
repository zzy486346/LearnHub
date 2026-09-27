package com.learnhub.search;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class CourseSearchServiceTest {
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
