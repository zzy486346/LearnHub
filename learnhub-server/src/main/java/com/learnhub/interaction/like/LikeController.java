package com.learnhub.interaction.like;

import com.learnhub.auth.security.LearnHubPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/likes")
public class LikeController {
    private final LikeService service;

    public LikeController(LikeService service) {
        this.service = service;
    }

    @PutMapping("/{type}/{targetId}")
    public LikeResult like(@AuthenticationPrincipal LearnHubPrincipal principal,
                           @PathVariable LikeTargetType type,
                           @PathVariable Long targetId) {
        return service.setLike(principal.userId(), type, targetId, true);
    }

    @DeleteMapping("/{type}/{targetId}")
    public LikeResult unlike(@AuthenticationPrincipal LearnHubPrincipal principal,
                             @PathVariable LikeTargetType type,
                             @PathVariable Long targetId) {
        return service.setLike(principal.userId(), type, targetId, false);
    }

    @GetMapping("/{type}/{targetId}")
    public LikeResult status(@AuthenticationPrincipal LearnHubPrincipal principal,
                             @PathVariable LikeTargetType type,
                             @PathVariable Long targetId) {
        return service.status(principal.userId(), type, targetId);
    }
}
