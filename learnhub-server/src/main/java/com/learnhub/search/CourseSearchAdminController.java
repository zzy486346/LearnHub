package com.learnhub.search;

import com.learnhub.common.api.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/search/courses")
public class CourseSearchAdminController {
    private final CourseIndexSyncService service;

    public CourseSearchAdminController(CourseIndexSyncService service) {
        this.service = service;
    }

    @PostMapping("/rebuild")
    public ApiResponse<CourseIndexSyncService.RebuildResult> rebuild() {
        return ApiResponse.success(service.adminFullRebuild());
    }

    @PostMapping("/sync")
    public ApiResponse<Integer> sync(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(service.syncDue(limit));
    }
}
