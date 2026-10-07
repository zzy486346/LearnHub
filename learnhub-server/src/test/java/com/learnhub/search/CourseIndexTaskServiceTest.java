package com.learnhub.search;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.junit.jupiter.api.Test;

class CourseIndexTaskServiceTest {
    @Test
    void enqueuesEachDistinctCourseOnce() {
        CourseIndexTaskMapper mapper = mock(CourseIndexTaskMapper.class);
        CourseIndexTaskService service = new CourseIndexTaskService(mapper);

        service.enqueueAll(List.of(11L, 12L, 11L));

        verify(mapper, times(1)).enqueue(11L);
        verify(mapper, times(1)).enqueue(12L);
    }
}
