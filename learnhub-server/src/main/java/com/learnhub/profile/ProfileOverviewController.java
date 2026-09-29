package com.learnhub.profile;

import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.common.api.ApiResponse;
import com.learnhub.profile.dto.ProfileOverviewResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileOverviewController {
    private final ProfileOverviewService service;

    public ProfileOverviewController(ProfileOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<ProfileOverviewResponse> overview(@AuthenticationPrincipal LearnHubPrincipal principal) {
        return ApiResponse.success(service.overview(principal.userId()));
    }
}
