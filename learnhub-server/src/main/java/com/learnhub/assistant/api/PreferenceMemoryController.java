package com.learnhub.assistant.api;

import com.learnhub.assistant.memory.PreferenceMemoryService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import com.learnhub.common.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix="learnhub.assistant", name="chat-enabled", havingValue="true")
@RequestMapping("/api/assistant/memories")
public class PreferenceMemoryController {
    private final PreferenceMemoryService service;
    public PreferenceMemoryController(PreferenceMemoryService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<PreferenceMemoryService.PreferenceView>> list(@AuthenticationPrincipal LearnHubPrincipal user) {
        return ApiResponse.success(service.list(user.userId()));
    }
    @PostMapping
    public ApiResponse<PreferenceMemoryService.PreferenceView> create(@AuthenticationPrincipal LearnHubPrincipal user,
                                                                    @Valid @RequestBody Create body) {
        return ApiResponse.success(service.create(user.userId(), body.category(), body.text(), body.sourceConversationId()));
    }
    @PatchMapping("/{id}")
    public ApiResponse<PreferenceMemoryService.PreferenceView> update(@AuthenticationPrincipal LearnHubPrincipal user,
                                                                    @PathVariable String id, @Valid @RequestBody Update body) {
        return ApiResponse.success(service.update(user.userId(), id, body.version(), body.category(), body.text()));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<PreferenceMemoryService.PreferenceView> forget(@AuthenticationPrincipal LearnHubPrincipal user,
                                                                    @PathVariable String id, @RequestParam long version) {
        if (version < 1) throw new BusinessException("MEMORY_INPUT_INVALID", "版本须为正数");
        return ApiResponse.success(service.forget(user.userId(), id, version));
    }
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> business(BusinessException error) {
        int status = switch (error.getCode()) {
            case "MEMORY_NOT_FOUND", "COURSE_NOT_FOUND" -> 404;
            case "MEMORY_VERSION_CONFLICT", "MEMORY_CHANGED" -> 409;
            case "MEMORY_DISABLED", "MEMORY_UNAVAILABLE" -> 503;
            case "MEMORY_LIMIT_EXCEEDED" -> 429;
            default -> 400;
        };
        return ResponseEntity.status(status).body(ApiResponse.failure(error.getCode(), error.getMessage()));
    }
    public record Create(@NotBlank @Pattern(regexp="STYLE|PACE|LEVEL|GOAL") String category,
                         @NotBlank @Size(max=200) String text,
                         @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String sourceConversationId) { }
    public record Update(@Positive long version,
                         @NotBlank @Pattern(regexp="STYLE|PACE|LEVEL|GOAL") String category,
                         @NotBlank @Size(max=200) String text) { }
}
