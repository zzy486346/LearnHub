package com.learnhub.auth.dto;

public record TokenResponse(String tokenType, String accessToken, long accessExpiresIn,
                            String refreshToken, long refreshExpiresIn) {}
