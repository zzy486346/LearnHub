package com.learnhub.course.dto;

import java.util.List;

public record ChapterDetailResponse(
        Long id,
        String title,
        Integer sortOrder,
        List<LessonDetailResponse> lessons
) {}
