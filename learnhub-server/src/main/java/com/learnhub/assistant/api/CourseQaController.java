package com.learnhub.assistant.api;

import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.runtime.*;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/api/assistant")
public class CourseQaController {
    private final CourseQaService qa;
    private final AssistantExecutionManager executions;
    private final AssistantSessionVerifier sessions;
    public CourseQaController(CourseQaService qa, AssistantExecutionManager executions, AssistantSessionVerifier sessions) {
        this.qa = qa; this.executions = executions; this.sessions = sessions;
    }
    @PostMapping("/course-qa")
    public DeferredResult<ResponseEntity<?>> answer(@AuthenticationPrincipal LearnHubPrincipal principal,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Assistant-Request-Id", required = false) String requestId,
            @Valid @RequestBody Question request) {
        var userId = principal == null ? null : principal.userId();
        return executions.submit(userId, requestId, sessions.checkpoint(userId, authorization),
                run -> qa.answer(userId, request.courseId(), request.question(), run));
    }
    @DeleteMapping("/runs/{requestId}")
    public ApiResponse<Boolean> cancel(@AuthenticationPrincipal LearnHubPrincipal principal, @PathVariable String requestId) {
        return ApiResponse.success(executions.cancel(principal == null ? null : principal.userId(), requestId));
    }
    public record Question(@NotNull @Positive Long courseId, @NotBlank @Size(max = 500) String question) { }
}
