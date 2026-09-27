package com.learnhub.interaction.qa;

import com.learnhub.common.api.ApiResponse;
import com.learnhub.auth.security.LearnHubPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    public ApiResponse<List<Question>> list(@RequestParam(required = false) Long courseId) {
        return ApiResponse.success(service.list(courseId));
    }

    @PostMapping("/{questionId}/answers")
    public ApiResponse<Question> answer(@AuthenticationPrincipal LearnHubPrincipal principal,
                           @PathVariable Long questionId,
                           @Valid @RequestBody QaRequests.CreateAnswer request) {
        return ApiResponse.success(service.answer(principal.userId(), questionId, request));
    }
}
