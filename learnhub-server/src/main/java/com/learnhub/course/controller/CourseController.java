package com.learnhub.course.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import com.learnhub.course.dto.CourseDetailResponse;
import com.learnhub.course.model.Course;
import com.learnhub.course.service.CourseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/courses")
public class CourseController {
    private final CourseService courseService;
    public CourseController(CourseService courseService) { this.courseService = courseService; }

    @GetMapping
    public ApiResponse<Page<Course>> list(@RequestParam(defaultValue = "1") long page,
                                          @RequestParam(defaultValue = "12") long size,
                                          @RequestParam(required = false) Long categoryId,
                                          @RequestParam(required = false) String keyword) {
        return ApiResponse.success(courseService.list(page, size, categoryId, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<CourseDetailResponse> detail(@PathVariable Long id,
                                                     @AuthenticationPrincipal LearnHubPrincipal principal) {
        return ApiResponse.success(courseService.detail(id, principal == null ? null : principal.userId()));
    }
}
