package com.learnhub.course.controller;

import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import com.learnhub.course.dto.ProgressUpdateRequest;
import com.learnhub.course.model.LearningProgress;
import com.learnhub.course.service.LearningProgressService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/learning/progress")
public class LearningProgressController {
    private final LearningProgressService progressService;
    public LearningProgressController(LearningProgressService progressService) { this.progressService = progressService; }

    @GetMapping("/{courseId}")
    public ApiResponse<List<LearningProgress>> get(@AuthenticationPrincipal LearnHubPrincipal principal,
                                                   @PathVariable Long courseId) {
        return ApiResponse.success(progressService.getByCourse(principal.userId(), courseId));
    }

    @PutMapping("/{courseId}")
    public ApiResponse<LearningProgress> save(@AuthenticationPrincipal LearnHubPrincipal principal,
                                              @PathVariable Long courseId,
                                              @Valid @RequestBody ProgressUpdateRequest request) {
        return ApiResponse.success(progressService.save(principal.userId(), courseId, request));
    }
}
