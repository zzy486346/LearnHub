package com.learnhub.assistant.knowledge;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(AdminKnowledgeController.class)
@Import(SecurityConfig.class)
class AdminKnowledgeControllerTest {
    @Autowired private MockMvc mvc;
    @MockBean private KnowledgeStore knowledgeStore;
    @MockBean private JwtTokenService tokenService;
    @MockBean private RoleService roleService;
    @MockBean private UserMapper userMapper;

    @Test
    void adminImportsValidatedDocumentAndReceivesStringId() throws Exception {
        when(knowledgeStore.importDocument(any())).thenReturn(
                new ImportDocumentResult("9007199254740993", 4L, 2, false));

        mvc.perform(post("/api/admin/knowledge/documents")
                        .with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceKey": "lesson-22-notes",
                                  "title": "课时讲义",
                                  "courseId": 11,
                                  "lessonId": 22,
                                  "scenes": [{"heading": "开场", "content": "正文"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.documentId").value("9007199254740993"))
                .andExpect(jsonPath("$.data.sourceVersion").value(4))
                .andExpect(jsonPath("$.data.chunkCount").value(2))
                .andExpect(jsonPath("$.data.unchanged").value(false));

        verify(knowledgeStore).importDocument(any(ImportDocumentRequest.class));
    }

    @Test
    void rejectsNullSceneBeforeStore() throws Exception {
        mvc.perform(post("/api/admin/knowledge/documents")
                        .with(authentication(admin()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceKey": "lesson-22-notes",
                                  "title": "课时讲义",
                                  "courseId": 11,
                                  "lessonId": 22,
                                  "scenes": [null]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private UsernamePasswordAuthenticationToken admin() {
        return new UsernamePasswordAuthenticationToken(new LearnHubPrincipal(1L, "admin"), null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }
}
