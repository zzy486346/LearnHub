package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.auth.security.JwtTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import org.junit.jupiter.api.Test;

class AssistantSessionVerifierTest {
    @Test @SuppressWarnings("unchecked") void rechecksLogoutAndVersionOnEveryCheckpoint() {
        var tokens = mock(JwtTokenService.class);
        var users = mock(UserMapper.class);
        Jws<Claims> parsed = mock(Jws.class);
        Claims claims = mock(Claims.class);
        when(parsed.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("7");
        when(claims.getId()).thenReturn("jti");
        when(tokens.require("local-test-token", "access")).thenReturn(parsed);
        var user = new User(); user.setId(7L); user.setStatus("ACTIVE"); user.setDeleted(0); user.setTokenVersion(0);
        when(users.selectById(7L)).thenReturn(user);
        Runnable checkpoint = new AssistantSessionVerifier(tokens, users).checkpoint(7L, "Bearer local-test-token");
        checkpoint.run();
        when(tokens.isAccessDenied("jti")).thenReturn(true);
        assertThatThrownBy(checkpoint::run).hasMessageContaining("登录状态");
        when(tokens.isAccessDenied("jti")).thenReturn(false);
        user.setTokenVersion(1);
        assertThatThrownBy(checkpoint::run).hasMessageContaining("登录状态");
        verify(users, times(3)).selectById(7L);
    }
}
