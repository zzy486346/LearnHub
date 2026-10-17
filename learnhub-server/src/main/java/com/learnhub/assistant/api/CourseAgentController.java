package com.learnhub.assistant.api;

import com.learnhub.assistant.agent.CourseAgentService;
import com.learnhub.assistant.runtime.*;
import com.learnhub.auth.security.LearnHubPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/api/assistant")
public class CourseAgentController {
    private final CourseAgentService agent;
    private final AssistantExecutionManager executions;
    private final AssistantSessionVerifier sessions;
    public CourseAgentController(CourseAgentService agent, AssistantExecutionManager executions, AssistantSessionVerifier sessions) {
        this.agent = agent; this.executions = executions; this.sessions = sessions;
    }
    @PostMapping("/agent")
    public DeferredResult<ResponseEntity<?>> answer(@AuthenticationPrincipal LearnHubPrincipal principal,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(value = "X-Assistant-Request-Id", required = false) String requestId,
            @Valid @RequestBody Goal request) {
        var userId = principal == null ? null : principal.userId();
        return executions.submit(userId, requestId, sessions.checkpoint(userId, authorization),
                run -> agent.answer(userId, request.courseId(), request.goal(), run));
    }
    public record Goal(@NotNull @Positive Long courseId, @NotBlank @Size(max = 500) String goal) { }
}
