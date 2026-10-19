package com.learnhub.assistant.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.api.AssistantExceptionHandler;
import com.learnhub.assistant.chat.api.ChatController;
import com.learnhub.assistant.chat.application.*;
import com.learnhub.assistant.chat.model.LangChainChatGenerator;
import com.learnhub.assistant.chat.model.LangChainChatGenerator.PreparedAnswer;
import com.learnhub.assistant.chat.model.LangChainChatGenerator.ToolStatus;
import com.learnhub.assistant.chat.stream.ChatStreamService;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.qa.*;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.auth.security.*;
import com.learnhub.auth.service.RoleService;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.config.SecurityConfig;
import com.learnhub.course.service.CourseService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.test.annotation.DirtiesContext;

/** 真实Servlet/HTTP/认证分派，随机回环端口；持久化和JWT解码为Mock，不宣称真实数据库登录验收。 */
@SpringBootTest(classes = ChatSseHttpIntegrationTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "learnhub.assistant.chat-enabled=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(25)
class ChatSseHttpIntegrationTest {
    @LocalServerPort int port;
    @MockBean JwtTokenService tokens;
    @MockBean UserMapper users;
    @MockBean RoleService roles;
    @MockBean ChatPersistenceService persistence;
    @MockBean CourseService courses;
    @MockBean CourseQaService qa;
    @MockBean AssistantRunStore store;
    @MockBean LangChainChatGenerator generator;
    @MockBean com.learnhub.assistant.tool.AssistantTools tools;
    @Autowired ObjectMapper mapper;
    @Autowired AiProperties ai;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    final AtomicReference<String> state = new AtomicReference<>();
    final AtomicReference<ChatPersistenceService.Run> active = new AtomicReference<>();
    final AtomicReference<String> answer = new AtomicReference<>();
    final AtomicInteger starts = new AtomicInteger();
    final String conversation = "owned-conversation";

    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        ai.setMode("FAKE");
        state.set(null); active.set(null); answer.set(null); starts.set(0);
        for (long owner : List.of(7L, 8L)) {
            Jws<Claims> jwt = mock(Jws.class); Claims claims = mock(Claims.class);
            when(tokens.require("fixture-" + owner, "access")).thenReturn(jwt);
            when(jwt.getPayload()).thenReturn(claims);
            when(claims.getSubject()).thenReturn(Long.toString(owner));
            when(claims.get("username", String.class)).thenReturn("fixture");
            when(claims.getId()).thenReturn("jti-" + owner);
            User user = new User(); user.setId(owner); user.setStatus("ACTIVE"); user.setDeleted(0); user.setTokenVersion(0);
            when(users.selectById(owner)).thenReturn(user);
            when(roles.rolesForUser(owner)).thenReturn(List.of("USER"));
        }
        when(store.acquire(anyString(), anyString(), anyLong(), anyInt(), anyInt())).thenReturn(AssistantRunStore.AcquireResult.ACQUIRED);
        when(store.reserveDaily(anyString(), anyString(), anyInt(), anyInt(), anyInt(), anyLong())).thenReturn(AssistantRunStore.ReserveResult.RESERVED);
        when(persistence.conversation(anyLong(), eq(conversation))).thenAnswer(call -> {
            if (call.<Long>getArgument(0) != 7L) throw new BusinessException("CHAT_NOT_FOUND", "会话不存在");
            return new ChatPersistenceService.Conversation(conversation, 7, 1, "课程会话", 1);
        });
        when(persistence.start(eq(7L), eq(conversation), anyString(), anyString(), any())).thenAnswer(call -> {
            if (active.get() != null) return new ChatPersistenceService.Started(current(), false);
            var run = new ChatPersistenceService.Run(UUID.randomUUID().toString(), conversation, 7,
                    call.getArgument(2), "RUNNING", "private-lease", call.getArgument(3));
            active.set(run); state.set("RUNNING"); starts.incrementAndGet();
            return new ChatPersistenceService.Started(run, true);
        });
        when(persistence.run(anyLong(), anyString())).thenAnswer(call -> {
            if (call.<Long>getArgument(0) != 7L || active.get() == null)
                throw new BusinessException("CHAT_NOT_FOUND", "运行不存在");
            return current();
        });
        when(persistence.messages(eq(7L), eq(conversation), anyLong(), anyInt())).thenAnswer(call -> List.of());
        when(persistence.complete(eq(7L), anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            boolean changed = state.compareAndSet("RUNNING", "COMPLETED");
            if (changed) answer.set(call.getArgument(3));
            return changed;
        });
        when(persistence.cancel(anyLong(), anyString())).thenAnswer(call -> {
            if (call.<Long>getArgument(0) != 7L) throw new BusinessException("CHAT_NOT_FOUND", "运行不存在");
            state.compareAndSet("RUNNING", "CANCELLED"); return current();
        });
        when(persistence.fail(anyLong(), anyString(), anyString(), anyString())).thenAnswer(call -> state.compareAndSet("RUNNING", call.getArgument(2)));
        when(qa.evidenceBook(7L, 1)).thenAnswer(call -> new EvidenceBook(7L, 1, mock(KnowledgeStore.class), courses, mapper));
    }

    @Test void fakeSseHasOrderedDeltasAndFinalAndDuplicateDoesNotGenerateAgain() throws Exception {
        String request = UUID.randomUUID().toString();
        var response = client.send(sendRequest(request, 7, "解释缓存击穿"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("text/event-stream");
        assertThat(response.body()).contains("event:accepted", "event:delta", "event:sources", "event:final", "FAKE")
                .doesNotContain("private-lease", "fixture-7");
        assertThat(response.body().split("event:delta", -1).length).isGreaterThan(2);
        assertThat(state).hasValue("COMPLETED"); assertThat(answer.get()).contains("模拟回答");
        var repeated = client.send(sendRequest(request, 7, "解释缓存击穿"), HttpResponse.BodyHandlers.ofString());
        assertThat(repeated.body()).contains("\"replayed\":true").doesNotContain("event:delta");
        assertThat(starts).hasValue(1);
    }

    @Test void stopAfterFirstDeltaProducesCancelledAndNeverSavesAnswer() throws Exception {
        var response = client.send(sendRequest(UUID.randomUUID().toString(), 7, "解释".repeat(200)), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            readUntilDelta(reader);
            var stopped = client.send(HttpRequest.newBuilder(uri("/runs/" + active.get().id() + "/cancel"))
                    .header("Authorization", "Bearer fixture-7").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(stopped.statusCode()).isEqualTo(200);
            String rest = String.join("\n", reader.lines().toList());
            assertThat(rest).contains("event:cancelled").doesNotContain("event:final");
        }
        assertThat(state).hasValue("CANCELLED"); assertThat(answer).hasValue(null);
    }

    @Test void revocationAfterCommitSuppressesSourcesAndFinalWithoutDowngradingDatabase() throws Exception {
        when(persistence.complete(eq(7L), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    boolean changed = state.compareAndSet("RUNNING", "COMPLETED");
                    if (changed) answer.set(call.getArgument(3));
                    doThrow(new BusinessException("COURSE_NOT_FOUND", "课程受限"))
                            .when(courses).requireAvailable(1L, 7L);
                    return changed;
                });
        var response = client.send(sendRequest(UUID.randomUUID().toString(), 7, "解释缓存"), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).contains("event:delta", "event:error", "\"clearDraft\":true")
                .doesNotContain("event:sources", "event:final");
        assertThat(state).hasValue("COMPLETED");
        verify(persistence, never()).fail(anyLong(), anyString(), anyString(), anyString());
    }

    @Test void revokedIdentityDuringStreamReturnsStructuredErrorAndDiscardsDraft() throws Exception {
        var response = client.send(sendRequest(UUID.randomUUID().toString(), 7, "解释".repeat(200)), HttpResponse.BodyHandlers.ofInputStream());
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            readUntilDelta(reader);
            when(tokens.isAccessDenied("jti-7")).thenReturn(true);
            String rest = String.join("\n", reader.lines().toList());
            assertThat(rest).contains("event:error", "ASSISTANT_SESSION_EXPIRED", "\"clearDraft\":true")
                    .doesNotContain("event:final", "\"code\":\"UNAUTHORIZED\"");
        }
        assertThat(state).hasValue("FAILED"); assertThat(answer).hasValue(null);
    }

    @Test void disconnectCancelsRunAndDoesNotPersistDraft() throws Exception {
        // BodyHandler.close可能排空短响应后复用连接；RST才能确定模拟服务端可观测的断连。
        byte[] body = mapper.writeValueAsBytes(Map.of("message", "解释".repeat(200), "clientRequestId", UUID.randomUUID().toString()));
        try (var socket = new java.net.Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.setSoLinger(true, 0);
            String headers = "POST /api/assistant/conversations/" + conversation + "/runs HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\nAuthorization: Bearer fixture-7\r\n"
                    + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\n\r\n";
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
            readUntilDelta(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)));
        }
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while ("RUNNING".equals(state.get()) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(state).hasValue("CANCELLED");
        assertThat(answer).hasValue(null);
    }

    @Test void anonymousAndForeignUserCannotEnterOrReadRun() throws Exception {
        var anonymous = HttpRequest.newBuilder(uri("/conversations/" + conversation + "/runs"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertThat(client.send(anonymous, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(client.send(sendRequest(UUID.randomUUID().toString(), 8, "越权"), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        assertThat(starts).hasValue(0);
    }

    @Test void toolEventsKeepWorkerOrderAndExposeOnlySafeStatusBeforeFinal() throws Exception {
        ai.setMode("DEEPSEEK");
        AtomicReference<String> worker = new AtomicReference<>();
        when(generator.prepare(eq(7L), any(), any(), any(), any())).thenAnswer(call -> {
            worker.set(Thread.currentThread().getName());
            Consumer<String> delta = call.getArgument(3);
            Consumer<ToolStatus> status = call.getArgument(4);
            status.accept(new ToolStatus("tool_start", Map.of("toolCallId", "safe-call-1",
                    "toolName", "get_course_outline", "status", "RUNNING")));
            status.accept(new ToolStatus("tool_end", Map.of("toolCallId", "safe-call-1",
                    "toolName", "get_course_outline", "status", "COMPLETED")));
            delta.accept("工具已完成。 ");
            return new PreparedAnswer("工具已完成。 ", "[]", "{}", List.of(), "DEEPSEEK");
        });

        var response = client.send(sendRequest(UUID.randomUUID().toString(), 7, "查看当前课程目录"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        List<SseEvent> events = parseEvents(response.body());
        assertThat(events.stream().map(SseEvent::type).toList())
                .containsSubsequence("accepted", "tool_start", "tool_end", "delta", "sources", "final");
        assertToolPayload(events, "tool_start", "RUNNING");
        assertToolPayload(events, "tool_end", "COMPLETED");
        assertThat(events.stream().map(event -> event.payload().path("seq").asLong()).toList())
                .isSorted().doesNotHaveDuplicates();
        assertThat(worker.get()).isEqualTo("assistant-run");
        assertThat(response.body()).doesNotContain("arguments", "result_summary", "resultSummary", "resultBody",
                "private-lease", "fixture-7");
        assertThat(state).hasValue("COMPLETED");
        assertThat(answer).hasValue("工具已完成。 ");
        verify(generator).prepare(eq(7L), any(), any(), any(), any());
        verify(generator, never()).prepare(anyLong(), any(), any(), any());
    }

    @Test void toolFailureReturnsOrderedErrorClearsDraftAndNeverCommitsAnswer() throws Exception {
        ai.setMode("DEEPSEEK");
        when(generator.prepare(eq(7L), any(), any(), any(), any())).thenAnswer(call -> {
            Consumer<String> delta = call.getArgument(3);
            Consumer<ToolStatus> status = call.getArgument(4);
            status.accept(new ToolStatus("tool_start", Map.of("toolCallId", "safe-call-1",
                    "toolName", "get_course_outline", "status", "RUNNING")));
            delta.accept("尚未完成的回答草稿");
            status.accept(new ToolStatus("tool_end", Map.of("toolCallId", "safe-call-1",
                    "toolName", "get_course_outline", "status", "FAILED")));
            throw new BusinessException("ASSISTANT_TOOL_UNAVAILABLE", "只读工具暂不可用");
        });

        var response = client.send(sendRequest(UUID.randomUUID().toString(), 7, "查看当前课程目录"),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        List<SseEvent> events = parseEvents(response.body());
        assertThat(events.stream().map(SseEvent::type).toList())
                .containsSubsequence("accepted", "tool_start", "delta", "tool_end", "error")
                .doesNotContain("sources", "final");
        assertToolPayload(events, "tool_end", "FAILED");
        var terminal = events.get(events.size() - 1);
        assertThat(terminal.type()).isEqualTo("error");
        assertThat(terminal.payload().path("code").asText()).isEqualTo("ASSISTANT_TOOL_UNAVAILABLE");
        assertThat(terminal.payload().path("clearDraft").asBoolean()).isTrue();
        assertThat(response.body()).doesNotContain("arguments", "resultBody", "private-lease", "fixture-7");
        assertThat(state).hasValue("FAILED");
        assertThat(answer).hasValue(null);
        verify(persistence, never()).complete(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private List<SseEvent> parseEvents(String body) throws IOException {
        List<SseEvent> result = new ArrayList<>();
        for (String frame : body.replace("\r\n", "\n").split("\n\n")) {
            String type = null;
            StringBuilder data = new StringBuilder();
            for (String line : frame.split("\n")) {
                if (line.startsWith("event:")) type = line.substring(6).trim();
                if (line.startsWith("data:")) data.append(line.substring(5).trim());
            }
            if (type != null && !data.isEmpty()) result.add(new SseEvent(type, mapper.readTree(data.toString())));
        }
        return result;
    }

    private void assertToolPayload(List<SseEvent> events, String type, String status) {
        var event = events.stream().filter(item -> item.type().equals(type)).findFirst().orElseThrow();
        List<String> fields = new ArrayList<>();
        event.payload().fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("eventId", "runId", "seq", "toolCallId", "toolName", "status");
        assertThat(event.payload().path("toolCallId").asText()).isEqualTo("safe-call-1");
        assertThat(event.payload().path("toolName").asText()).isEqualTo("get_course_outline");
        assertThat(event.payload().path("status").asText()).isEqualTo(status);
    }

    private record SseEvent(String type, com.fasterxml.jackson.databind.JsonNode payload) { }

    private ChatPersistenceService.Run current() {
        var run = active.get();
        return new ChatPersistenceService.Run(run.id(), run.conversationId(), run.ownerUserId(), run.clientRequestId(), state.get(), run.leaseOwner(), run.message());
    }
    private HttpRequest sendRequest(String id, long owner, String message) throws Exception {
        return HttpRequest.newBuilder(uri("/conversations/" + conversation + "/runs")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer fixture-" + owner).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("message", message, "clientRequestId", id)))).build();
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + "/api/assistant" + path); }
    private static void readUntilDelta(BufferedReader reader) throws IOException {
        for (String line; (line = reader.readLine()) != null;) if (line.equals("event:delta")) return;
        throw new AssertionError("Stream ended before first delta");
    }

    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration
    @Import({SecurityConfig.class, JwtAuthenticationFilter.class, AssistantExceptionHandler.class,
            ChatController.class, ChatConversationService.class, ChatHistoryService.class, ChatStreamService.class,
            com.learnhub.assistant.chat.application.ChatEvidenceCodec.class,
            AssistantExecutionManager.class, AssistantRunGuard.class, AssistantSessionVerifier.class})
    static class App {
        @Bean AssistantLimits limits() { var limits = new AssistantLimits(); limits.setTimeout(Duration.ofSeconds(5)); return limits; }
        @Bean AiProperties ai() { var properties = new AiProperties(); properties.setMode("FAKE"); return properties; }
    }
}
