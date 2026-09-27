package com.learnhub.auth.controller;

import com.learnhub.auth.dto.LoginRequest;
import com.learnhub.auth.dto.RefreshTokenRequest;
import com.learnhub.auth.dto.RegisterRequest;
import com.learnhub.auth.dto.TokenResponse;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.auth.service.AuthService;
import com.learnhub.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }

    @PostMapping("/register")
    public ApiResponse<Long> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ApiResponse.success(authService.refresh(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                    @RequestBody(required = false) RefreshTokenRequest request) {
        String access = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        authService.logout(access, request == null ? null : request.refreshToken());
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    public ApiResponse<LearnHubPrincipal> me(@AuthenticationPrincipal LearnHubPrincipal principal) {
        return ApiResponse.success(principal);
    }
}
