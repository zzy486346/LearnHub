package com.learnhub.auth.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.auth.service.AuthService;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthControllerSecurityTest {
    @Autowired private MockMvc mvc;
    @MockBean private AuthService authService;
    @MockBean private JwtTokenService tokenService;
    @MockBean private RoleService roleService;
    @MockBean private UserMapper userMapper;

    @Test
    void expiredAccessTokenDoesNotBlockRefreshTokenRevocation() throws Exception {
        when(tokenService.require("expired-access", "access")).thenThrow(new JwtException("expired"));

        mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer expired-access")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"valid-refresh\"}"))
                .andExpect(status().isOk());

        verify(authService).logout("expired-access", "valid-refresh");
    }

    @Test
    void repeatedLogoutWithoutCredentialsIsIdempotent() throws Exception {
        mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk());

        verify(authService).logout(null, null);
    }
}
