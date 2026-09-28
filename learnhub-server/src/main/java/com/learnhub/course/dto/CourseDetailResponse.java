package com.learnhub.course.dto;

import java.math.BigDecimal;
import java.util.List;

public record CourseDetailResponse(
        Long id,
        String title,
        String description,
        String coverUrl,
        String instructor,
        Long categoryId,
        BigDecimal price,
        String status,
        Long likeCount,
        List<ChapterDetailResponse> chapters
) {}
