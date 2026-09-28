package com.learnhub.interaction.qa;

import com.learnhub.common.api.ApiResponse;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/questions")
public class QaController {
    private final QaService service;

    public QaController(QaService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<Question> create(@AuthenticationPrincipal LearnHubPrincipal principal,
                           @Valid @RequestBody QaRequests.CreateQuestion request) {
        return ApiResponse.success(service.create(principal.userId(), request));
    }

    @GetMapping
    public ApiResponse<Page<Question>> list(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "10") long size,
                                            @RequestParam(required = false) Long courseId) {
        return ApiResponse.success(service.list(page, size, courseId));
    }

    @GetMapping("/{questionId}")
    public ApiResponse<Question> detail(@PathVariable Long questionId) {
        return ApiResponse.success(service.detail(questionId));
    }

    @GetMapping("/{questionId}/answers")
    public ApiResponse<Page<Answer>> answers(@PathVariable Long questionId,
                                             @RequestParam(defaultValue = "1") long page,
                                             @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.success(service.listAnswers(questionId, page, size));
    }

    @PostMapping("/{questionId}/answers")
    public ApiResponse<Question> answer(@AuthenticationPrincipal LearnHubPrincipal principal,
                           @PathVariable Long questionId,
                           @Valid @RequestBody QaRequests.CreateAnswer request) {
        return ApiResponse.success(service.answer(principal.userId(), questionId, request));
    }
}
