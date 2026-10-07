package com.learnhub.course.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public final class AdminCourseRequests {
    private AdminCourseRequests() {}

    public record CreateCourse(
            @NotNull Long categoryId,
            @NotBlank @Size(max = 160) String title,
            @Size(max = 5000) String description,
            @NotBlank @Size(max = 80) String instructor,
            @NotNull @DecimalMin("0.00") BigDecimal price,
            @Pattern(regexp = "DRAFT|PUBLISHED") String status
    ) {}

    public record UpdateCourse(
            Long categoryId,
            @Size(max = 160) String title,
            @Size(max = 255) String subtitle,
            @Size(max = 512) String coverUrl,
            @Size(max = 5000) String description,
            @Size(max = 80) String instructor,
            @DecimalMin("0.00") BigDecimal price
    ) {}

    public record CreateChapter(
            @NotBlank @Size(max = 160) String title,
            @NotNull @Min(0) Integer sortOrder
    ) {}

    public record CreateLesson(
            @NotBlank @Size(max = 160) String title,
            @NotNull @Min(1) Integer durationSeconds,
            @NotNull Boolean freePreview,
            @NotNull @Min(0) Integer sortOrder
    ) {}
}
