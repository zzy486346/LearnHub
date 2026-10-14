package com.learnhub.course.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.math.BigDecimal;
import java.util.List;

public record CourseDetailResponse(
        Long id,
        String title,
        String subtitle,
        String description,
        String coverUrl,
        String instructor,
        @JsonSerialize(using = ToStringSerializer.class) Long categoryId,
        BigDecimal price,
        String status,
        Long likeCount,
        String accessLevel,
        List<ChapterDetailResponse> chapters
) {}
