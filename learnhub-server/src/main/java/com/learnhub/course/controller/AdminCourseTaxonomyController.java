package com.learnhub.course.controller;

import com.learnhub.common.api.ApiResponse;
import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.dto.CourseTaxonomyRequests;
import com.learnhub.course.model.CourseCategory;
import com.learnhub.course.model.CourseTag;
import com.learnhub.course.service.CourseTaxonomyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminCourseTaxonomyController {
    private final CourseTaxonomyService service;

    public AdminCourseTaxonomyController(CourseTaxonomyService service) {
        this.service = service;
    }

    @GetMapping("/course-categories")
    public ApiResponse<List<CourseCategory>> categories() {
        return ApiResponse.success(service.categories());
    }

    @PostMapping("/course-categories")
    public ApiResponse<String> createCategory(@Valid @RequestBody CourseTaxonomyRequests.CreateCategory request) {
        return ApiResponse.success(service.createCategory(request).toString());
    }

    @PutMapping("/course-categories/{categoryId}")
    public ApiResponse<Void> updateCategory(@PathVariable Long categoryId,
                                            @Valid @RequestBody CourseTaxonomyRequests.UpdateCategory request) {
        service.updateCategory(categoryId, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/course-categories/{categoryId}")
    public ApiResponse<Void> deleteCategory(@PathVariable Long categoryId) {
        service.deleteCategory(categoryId);
        return ApiResponse.success(null);
    }

    @GetMapping("/course-tags")
    public ApiResponse<List<CourseTag>> tags() {
        return ApiResponse.success(service.tags());
    }

    @PostMapping("/course-tags")
    public ApiResponse<String> createTag(@Valid @RequestBody CourseTaxonomyRequests.CreateTag request) {
        return ApiResponse.success(service.createTag(request).toString());
    }

    @PutMapping("/course-tags/{tagId}")
    public ApiResponse<Void> updateTag(@PathVariable Long tagId,
                                      @Valid @RequestBody CourseTaxonomyRequests.UpdateTag request) {
        service.updateTag(tagId, request);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/course-tags/{tagId}")
    public ApiResponse<Void> deleteTag(@PathVariable Long tagId) {
        service.deleteTag(tagId);
        return ApiResponse.success(null);
    }

    @GetMapping("/courses/{courseId}/tags")
    public ApiResponse<List<CourseTag>> courseTags(@PathVariable Long courseId) {
        return ApiResponse.success(service.courseTags(courseId));
    }

    @PutMapping("/courses/{courseId}/tags")
    public ApiResponse<Void> replaceCourseTags(@PathVariable Long courseId,
                                               @Valid @RequestBody AdminCourseRequests.ReplaceTags request) {
        service.replaceCourseTags(courseId, request);
        return ApiResponse.success(null);
    }
}
