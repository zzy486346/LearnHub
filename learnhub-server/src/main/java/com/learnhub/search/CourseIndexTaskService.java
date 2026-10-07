package com.learnhub.search;

import java.util.Collection;
import org.springframework.stereotype.Service;

@Service
public class CourseIndexTaskService {
    private final CourseIndexTaskMapper mapper;

    public CourseIndexTaskService(CourseIndexTaskMapper mapper) {
        this.mapper = mapper;
    }

    public void enqueue(Long courseId) {
        if (courseId != null) mapper.enqueue(courseId);
    }

    public void enqueueAll(Collection<Long> courseIds) {
        if (courseIds == null) return;
        courseIds.stream().filter(java.util.Objects::nonNull).distinct().forEach(mapper::enqueue);
    }
}
