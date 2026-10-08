package com.learnhub.interaction.like;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import io.jsonwebtoken.JwtException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LikeController.class)
@Import(SecurityConfig.class)
class LikeControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private LikeService service;
    @MockBean private JwtTokenService tokenService;
    @MockBean private RoleService roleService;
    @MockBean private UserMapper userMapper;

    @Test
    void guestCanReadQuestionCountWithoutPersonalState() throws Exception {
        when(service.count(LikeTargetType.QUESTION, 6001L)).thenReturn(3L);
        mvc.perform(get("/api/likes/QUESTION/6001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    void guestCannotVoteOrCancel() throws Exception {
        mvc.perform(put("/api/likes/QUESTION/6001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mvc.perform(delete("/api/likes/ANSWER/7001"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(service);
    }

    @Test
    void expiredBearerTokenReturnsUnauthorizedForProtectedOperation() throws Exception {
        when(tokenService.require("expired-token", "access")).thenThrow(new JwtException("expired"));

        mvc.perform(put("/api/likes/QUESTION/6001")
                        .header("Authorization", "Bearer expired-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("登录状态已失效，请重新登录"));
        verifyNoInteractions(service);
    }

    @Test
    void authenticatedNonAdminStillReceivesForbiddenForAdminOperation() throws Exception {
        var user = new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(10L, "test"), null, List.of());

        mvc.perform(put("/api/admin/security-probe").with(authentication(user)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void authenticatedUserCanVoteReadAndCancelForQuestionsAndAnswers() throws Exception {
        var user = new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(10L, "test"), null, List.of());
        for (LikeTargetType type : List.of(LikeTargetType.QUESTION, LikeTargetType.ANSWER)) {
            String path = "/api/likes/" + type + "/6001";
            when(service.setLike(10L, type, 6001L, true)).thenReturn(new LikeResult(true, 1));
            when(service.status(10L, type, 6001L)).thenReturn(new LikeResult(true, 1));
            when(service.setLike(10L, type, 6001L, false)).thenReturn(new LikeResult(false, 0));
            mvc.perform(put(path).with(authentication(user)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.liked").value(true));
            mvc.perform(get(path).with(authentication(user)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.liked").value(true));
            mvc.perform(delete(path).with(authentication(user)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(0));
            verify(service).setLike(10L, type, 6001L, true);
            verify(service).setLike(10L, type, 6001L, false);
        }
    }
}
