package com.learnhub.assistant.knowledge;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.learnhub.assistant.api.KnowledgeRetrievalController;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(KnowledgeRetrievalController.class)
@Import(SecurityConfig.class)
class KnowledgeRetrievalControllerTest {
    @Autowired MockMvc mvc;
    @MockBean KnowledgeRetrievalService service;
    @MockBean JwtTokenService tokenService;
    @MockBean RoleService roleService;
    @MockBean UserMapper userMapper;
    final UsernamePasswordAuthenticationToken user = new UsernamePasswordAuthenticationToken(
            new LearnHubPrincipal(7L, "learner"), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));

    @Test void anonymousCannotRetrieve() throws Exception {
        mvc.perform(post("/api/assistant/retrieve").contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":\"1\",\"question\":\"Lua\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void identityComesFromPrincipalAndLimitDefaultsToFive() throws Exception {
        mvc.perform(post("/api/assistant/retrieve").with(authentication(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":\"1\",\"question\":\"Lua\",\"userId\":999}")).andExpect(status().isOk());
        verify(service).retrieve(7L, 1L, "Lua", 5);
    }

    @Test void invalidQueryRejectedBeforeService() throws Exception {
        mvc.perform(post("/api/assistant/retrieve").with(authentication(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":\"1\",\"question\":\" \",\"limit\":100}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
