package com.learnhub.course.controller;

import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import com.learnhub.course.dto.AdminCourseRequests;
import com.learnhub.course.dto.CourseDetailResponse;
import com.learnhub.course.model.Course;
import com.learnhub.course.service.AdminCourseService;
import com.learnhub.storage.MediaResponses;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin")
public class AdminCourseController {
    private final AdminCourseService service;

    public AdminCourseController(AdminCourseService service) {
        this.service = service;
    }

    @GetMapping("/courses")
    public ApiResponse<List<Course>> courses() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/courses/{courseId}")
    public ApiResponse<CourseDetailResponse> detail(@PathVariable Long courseId) {
        return ApiResponse.success(service.detail(courseId));
    }

    @PostMapping("/courses")
    public ApiResponse<Long> createCourse(@Valid @RequestBody AdminCourseRequests.CreateCourse request) {
        return ApiResponse.success(service.createCourse(request));
    }

    @PostMapping("/courses/{courseId}/chapters")
    public ApiResponse<Long> createChapter(@PathVariable Long courseId,
                                           @Valid @RequestBody AdminCourseRequests.CreateChapter request) {
        return ApiResponse.success(service.createChapter(courseId, request));
    }

    @PostMapping("/chapters/{chapterId}/lessons")
    public ApiResponse<Long> createLesson(@PathVariable Long chapterId,
                                          @Valid @RequestBody AdminCourseRequests.CreateLesson request) {
        return ApiResponse.success(service.createLesson(chapterId, request));
    }

    @PostMapping(value = "/lessons/{lessonId}/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<MediaResponses.Asset> uploadLessonVideo(
            @AuthenticationPrincipal LearnHubPrincipal principal,
            @PathVariable Long lessonId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(service.uploadLessonVideo(principal.userId(), lessonId, file));
    }
}
