package com.learnhub.auth.dto;

import java.util.List;

public record CurrentUserResponse(Long id, String username, String nickname, String avatarUrl,
                                  List<String> roles) {}
