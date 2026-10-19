package com.learnhub.assistant.memory;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.learnhub.assistant.api.PreferenceMemoryController;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.JwtTokenService;
import com.learnhub.auth.security.LearnHubPrincipal;
import com.learnhub.auth.service.RoleService;
import com.learnhub.common.exception.BusinessException;
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

@WebMvcTest(value=PreferenceMemoryController.class, properties="learnhub.assistant.chat-enabled=true")
@Import(SecurityConfig.class)
class PreferenceMemoryControllerTest {
    @Autowired MockMvc mvc;
    @MockBean PreferenceMemoryService service;
    @MockBean JwtTokenService tokenService;
    @MockBean RoleService roleService;
    @MockBean UserMapper userMapper;
    private final UsernamePasswordAuthenticationToken user = new UsernamePasswordAuthenticationToken(
            new LearnHubPrincipal(7L, "learner"), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    private static final String ID = "12345678-1234-1234-1234-123456789012";

    @Test void anonymousCannotReadOrMutate() throws Exception {
        mvc.perform(get("/api/assistant/memories")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/assistant/memories").contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"STYLE\",\"text\":\"先举例\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void derivesOwnerFromPrincipalAndIgnoresInjectedOwner() throws Exception {
        mvc.perform(post("/api/assistant/memories").with(authentication(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"STYLE\",\"text\":\"先举例\",\"owner\":999,\"user_id\":999}"))
                .andExpect(status().isOk());
        verify(service).create(7L, "STYLE", "先举例", null);
        mvc.perform(get("/api/assistant/memories").with(authentication(user))).andExpect(status().isOk());
        verify(service).list(7);
    }
    @Test void rejectsInvalidBodyBeforePersistence() throws Exception {
        for (String body : List.of("{\"category\":\"PASSWORD\",\"text\":\"内容\"}",
                "{\"category\":\"STYLE\",\"text\":\" \"}",
                "{\"category\":\"STYLE\",\"text\":\"内容\",\"sourceConversationId\":\"foreign-string\"}"))
            mvc.perform(post("/api/assistant/memories").with(authentication(user)).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/assistant/memories/" + ID).with(authentication(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"category\":\"STYLE\",\"text\":\"先举例\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/assistant/memories/" + ID).param("version", "-1").with(authentication(user)))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void updateAndForgetUseOnlyAuthenticatedOwnerAndExplicitVersion() throws Exception {
        mvc.perform(patch("/api/assistant/memories/" + ID).with(authentication(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":2,\"category\":\"PACE\",\"text\":\"每天学习一次\",\"owner\":999}"))
                .andExpect(status().isOk());
        verify(service).update(7, ID, 2, "PACE", "每天学习一次");
        mvc.perform(delete("/api/assistant/memories/" + ID).param("version", "3").with(authentication(user)))
                .andExpect(status().isOk());
        verify(service).forget(7, ID, 3);
    }
    @Test void disabledMemoryReportsExplicitUnavailable() throws Exception {
        when(service.list(7)).thenThrow(new BusinessException("MEMORY_DISABLED", "未启用"));
        mvc.perform(get("/api/assistant/memories").with(authentication(user)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("MEMORY_DISABLED"));
    }
    @Test void foreignPreferenceIs404AndVersionConflictIs409() throws Exception {
        when(service.update(7, ID, 1, "STYLE", "先举例")).thenThrow(new BusinessException("MEMORY_NOT_FOUND", "不存在"));
        mvc.perform(patch("/api/assistant/memories/" + ID).with(authentication(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1,\"category\":\"STYLE\",\"text\":\"先举例\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("MEMORY_NOT_FOUND"));
        when(service.forget(7, ID, 1)).thenThrow(new BusinessException("MEMORY_VERSION_CONFLICT", "冲突"));
        mvc.perform(delete("/api/assistant/memories/" + ID).param("version", "1").with(authentication(user)))
                .andExpect(status().isConflict());
    }
    @Test void preferenceQuotaReports429WithoutPretendingToSave() throws Exception {
        when(service.create(7L, "STYLE", "先举例", null))
                .thenThrow(new BusinessException("MEMORY_LIMIT_EXCEEDED", "最多保存50条学习偏好"));
        mvc.perform(post("/api/assistant/memories").with(authentication(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"STYLE\",\"text\":\"先举例\"}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("MEMORY_LIMIT_EXCEEDED"));
    }
}
