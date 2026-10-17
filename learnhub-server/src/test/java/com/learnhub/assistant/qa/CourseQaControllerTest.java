package com.learnhub.assistant.qa;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.learnhub.assistant.api.CourseQaController;
import com.learnhub.assistant.runtime.*;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.security.*;
import com.learnhub.auth.service.RoleService;
import com.learnhub.config.SecurityConfig;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.request.async.DeferredResult;

@WebMvcTest({CourseQaController.class, com.learnhub.assistant.api.CourseAgentController.class}) @Import(SecurityConfig.class)
class CourseQaControllerTest {
    @Autowired MockMvc mvc;
    @MockBean CourseQaService qa;
    @MockBean com.learnhub.assistant.agent.CourseAgentService agent;
    @MockBean AssistantExecutionManager executions;
    @MockBean AssistantSessionVerifier sessions;
    @MockBean JwtTokenService tokens;
    @MockBean RoleService roles;
    @MockBean UserMapper users;
    final UsernamePasswordAuthenticationToken user = new UsernamePasswordAuthenticationToken(
            new LearnHubPrincipal(7L, "learner"), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    @BeforeEach @SuppressWarnings("unchecked") void validBearer() {
        io.jsonwebtoken.Jws<io.jsonwebtoken.Claims> token = mock(io.jsonwebtoken.Jws.class);
        var claims = mock(io.jsonwebtoken.Claims.class);
        when(tokens.require("local", "access")).thenReturn(token);
        when(token.getPayload()).thenReturn(claims);
        when(claims.getSubject()).thenReturn("7");
        when(claims.get("username", String.class)).thenReturn("learner");
        when(claims.getId()).thenReturn("test-jti");
        var activeUser = new com.learnhub.auth.model.User();
        activeUser.setId(7L); activeUser.setStatus("ACTIVE"); activeUser.setDeleted(0); activeUser.setTokenVersion(0);
        when(users.selectById(7L)).thenReturn(activeUser);
        when(roles.rolesForUser(7L)).thenReturn(List.of("USER"));
    }
    @Test void anonymousRejected() throws Exception {
        mvc.perform(post("/api/assistant/course-qa").contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":1,\"question\":\"Lua\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(executions);
    }
    @Test void identityBoundToPrincipalIgnoringForgedUserId() throws Exception {
        Runnable identity = () -> {};
        when(sessions.checkpoint(7L, "Bearer local")).thenReturn(identity);
        when(executions.submit(eq(7L), isNull(), eq(identity), any())).thenReturn(new DeferredResult<>());
        mvc.perform(post("/api/assistant/course-qa").with(authentication(user)).header("Authorization", "Bearer local")
                .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":1,\"question\":\"Lua\",\"userId\":999}"))
                .andExpect(request().asyncStarted());
        verify(sessions).checkpoint(7L, "Bearer local");
        verify(executions).submit(eq(7L), isNull(), eq(identity), any());
    }
    @Test void invalidInputDoesNotStartRun() throws Exception {
        mvc.perform(post("/api/assistant/course-qa").with(authentication(user)).header("Authorization", "Bearer local")
                .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":0,\"question\":\" \"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(executions);
    }
    @Test void cancelUsesOnlyCurrentPrincipal() throws Exception {
        mvc.perform(delete("/api/assistant/runs/test-id").with(authentication(user))).andExpect(status().isOk());
        verify(executions).cancel(7L, "test-id");
    }
    @Test void agentUsesLoginIdentity() throws Exception {
        Runnable identity = () -> {};
        when(sessions.checkpoint(7L, "Bearer local")).thenReturn(identity);
        when(executions.submit(eq(7L), isNull(), eq(identity), any())).thenReturn(new DeferredResult<>());
        mvc.perform(post("/api/assistant/agent").with(authentication(user)).header("Authorization", "Bearer local")
                .contentType(MediaType.APPLICATION_JSON).content("{\"courseId\":1,\"goal\":\"建议下一步\",\"userId\":999}"))
                .andExpect(request().asyncStarted());
        verify(executions).submit(eq(7L), isNull(), eq(identity), any());
    }
    @Test void authenticatedRagResponseSurvivesAsyncDispatch() throws Exception {
        assertAsyncResponse("/api/assistant/course-qa", "question");
    }
    @Test void authenticatedAgentResponseSurvivesAsyncDispatch() throws Exception {
        assertAsyncResponse("/api/assistant/agent", "goal");
    }
    @Test void asynchronousServiceFailureKeepsItsStatusInsteadOfBecomingUnauthorized() throws Exception {
        assertAsyncResponse("/api/assistant/course-qa", "question", () -> {}, 503, 503);
    }
    @Test void revokedIdentityBeforeAsyncResponseIsRejected() throws Exception {
        assertAsyncResponse("/api/assistant/course-qa", "question", () -> when(users.selectById(7L)).thenReturn(null), 200, 401);
    }
    @Test void deniedTokenBeforeAsyncResponseIsRejected() throws Exception {
        assertAsyncResponse("/api/assistant/agent", "goal", () -> when(tokens.isAccessDenied("test-jti")).thenReturn(true), 200, 401);
    }
    private void assertAsyncResponse(String path, String field) throws Exception {
        assertAsyncResponse(path, field, () -> {}, 200, 200);
    }
    private void assertAsyncResponse(String path, String field, Runnable beforeDispatch,
                                     int responseStatus, int expectedStatus) throws Exception {
        Runnable identity = () -> {};
        var pending = new DeferredResult<org.springframework.http.ResponseEntity<?>>();
        when(sessions.checkpoint(7L, "Bearer local")).thenReturn(identity);
        when(executions.submit(eq(7L), isNull(), eq(identity), any())).thenReturn(pending);
        var initial = mvc.perform(post(path).header("Authorization", "Bearer local")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":1,\"" + field + "\":\"fixture question\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        beforeDispatch.run();
        pending.setResult(org.springframework.http.ResponseEntity.status(responseStatus).body(java.util.Map.of("status", "completed")));
        var dispatched = mvc.perform(asyncDispatch(initial)).andExpect(status().is(expectedStatus));
        if (expectedStatus != 401) dispatched.andExpect(jsonPath("$.status").value("completed"));
        verify(executions, times(1)).submit(eq(7L), isNull(), eq(identity), any());
        verify(tokens, times(2)).require("local", "access");
    }
}
