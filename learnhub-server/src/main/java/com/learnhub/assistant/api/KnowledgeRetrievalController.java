package com.learnhub.assistant.api;

import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assistant")
public class KnowledgeRetrievalController {
    private final KnowledgeRetrievalService service;
    public KnowledgeRetrievalController(KnowledgeRetrievalService service) { this.service = service; }

    @PostMapping("/retrieve")
    public ApiResponse<KnowledgeRetrievalService.Result> retrieve(@AuthenticationPrincipal LearnHubPrincipal principal,
            @Valid @RequestBody RetrieveRequest request) {
        return ApiResponse.success(service.retrieve(principal == null ? null : principal.userId(),
                request.courseId(), request.question(), request.limit()));
    }
    public record RetrieveRequest(@NotNull @Positive Long courseId, @NotBlank @Size(max = 500) String question,
                                  @Min(1) @Max(10) Integer limit) {
        public RetrieveRequest { if (limit == null) limit = 5; }
    }
}
