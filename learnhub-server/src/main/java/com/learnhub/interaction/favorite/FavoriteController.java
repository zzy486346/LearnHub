package com.learnhub.interaction.favorite;

import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import com.learnhub.interaction.like.LikeTargetType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {
    private final FavoriteService service;

    public FavoriteController(FavoriteService service) {
        this.service = service;
    }

    @GetMapping("/{type}/{targetId}")
    public ApiResponse<Boolean> status(@AuthenticationPrincipal LearnHubPrincipal principal,
                                       @PathVariable LikeTargetType type,
                                       @PathVariable Long targetId) {
        return ApiResponse.success(service.exists(principal.userId(), type, targetId));
    }

    @PutMapping("/{type}/{targetId}")
    public ApiResponse<Boolean> favorite(@AuthenticationPrincipal LearnHubPrincipal principal,
                                         @PathVariable LikeTargetType type,
                                         @PathVariable Long targetId) {
        return ApiResponse.success(service.favorite(principal.userId(), type, targetId));
    }

    @DeleteMapping("/{type}/{targetId}")
    public ApiResponse<Boolean> unfavorite(@AuthenticationPrincipal LearnHubPrincipal principal,
                                           @PathVariable LikeTargetType type,
                                           @PathVariable Long targetId) {
        return ApiResponse.success(service.unfavorite(principal.userId(), type, targetId));
    }
}
