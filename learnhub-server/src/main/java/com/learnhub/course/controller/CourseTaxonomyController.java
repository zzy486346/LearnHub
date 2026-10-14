package com.learnhub.course.controller;

import com.learnhub.common.api.ApiResponse;
import com.learnhub.course.model.CourseCategory;
import com.learnhub.course.model.CourseTag;
import com.learnhub.course.service.CourseTaxonomyService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CourseTaxonomyController {
    private final CourseTaxonomyService service;

    public CourseTaxonomyController(CourseTaxonomyService service) {
        this.service = service;
    }

    @GetMapping("/api/course-categories")
    public ApiResponse<List<CourseCategory>> categories() {
        return ApiResponse.success(service.publicCategories());
    }

    @GetMapping("/api/course-tags")
    public ApiResponse<List<CourseTag>> tags() {
        return ApiResponse.success(service.publicTags());
    }
}
