package com.learnhub.course.dto;

public record LessonDetailResponse(
        Long id,
        String title,
        String mediaUrl,
        Integer durationSeconds,
        Boolean freePreview,
        Integer sortOrder
) {}
