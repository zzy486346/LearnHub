package com.learnhub.experiments;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AiServicesGuardCompatibilityTest {

    private MockWebServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdown();
    }

    @Test
    void aiServicesUsesToolsMemoryAndInvokesGuardForEveryAutomaticRound() throws Exception {
        server.enqueue(sse(toolCallChunk("call_outline", "outline", "{\"courseId\":7}")));
        server.enqueue(sse(textChunks("第一轮", "完成")));
        server.enqueue(sse(textChunks("记得课程7", "并继续回答")));

        RoundGuard guard = new RoundGuard(3);
        Assistant assistant = assistant(new GuardedStreamingChatModel(realModel(), guard));

        StreamResult first = consume(assistant.chat("conversation-a", "查课程7目录"));
        StreamResult second = consume(assistant.chat("conversation-a", "刚才查的是哪个课程？"));

        assertThat(first.error()).isNull();
        assertThat(first.text()).isEqualTo("第一轮完成");
        assertThat(second.error()).isNull();
        assertThat(second.text()).isEqualTo("记得课程7并继续回答");
        assertThat(guard.invocations()).isEqualTo(3);
        assertThat(server.getRequestCount()).isEqualTo(3);

        RecordedRequest initial = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest toolResultRound = server.takeRequest(1, TimeUnit.SECONDS);
        RecordedRequest followUp = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(initial.getBody().readUtf8()).contains("查课程7目录").doesNotContain("local-mock-key");
        assertThat(toolResultRound.getBody().readUtf8()).contains("Redis、Lua、缓存击穿");
        assertThat(followUp.getBody().readUtf8())
                .contains("查课程7目录", "第一轮完成", "刚才查的是哪个课程？");
    }

    @Test
    void guardCanRejectTheAutomaticModelRoundAfterToolExecution() throws Exception {
        server.enqueue(sse(toolCallChunk("call_outline", "outline", "{\"courseId\":7}")));

        RoundGuard guard = new RoundGuard(1);
        Assistant assistant = assistant(new GuardedStreamingChatModel(realModel(), guard));
        StreamResult result = consume(assistant.chat("conversation-b", "查课程7目录"));

        assertThat(result.error()).isInstanceOf(GuardRejectedException.class)
                .hasMessageContaining("模型回合预算已用尽");
        assertThat(guard.invocations()).isEqualTo(2);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    private Assistant assistant(StreamingChatModel model) {
        return AiServices.builder(Assistant.class)
                .streamingChatModel(model)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(12)
                        .build())
                .tools(new CourseTools())
                .maxToolCallingRoundTrips(3)
                .build();
    }

    private OpenAiStreamingChatModel realModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(server.url("/v1").toString())
                .apiKey("local-mock-key")
                .modelName("mock-model")
                .timeout(Duration.ofSeconds(2))
                .accumulateToolCallId(false)
                .logRequests(false)
                .logResponses(false)
                .build();
    }

    private static StreamResult consume(TokenStream stream) throws InterruptedException {
        StringBuilder text = new StringBuilder();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        stream.onPartialResponse(text::append)
                .onCompleteResponse(response -> done.countDown())
                .onError(throwable -> {
                    error.set(throwable);
                    done.countDown();
                })
                .start();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        return new StreamResult(text.toString(), error.get());
    }

    private static MockResponse sse(List<String> events) {
        String body = String.join("\n\n", events) + "\n\ndata: [DONE]\n\n";
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body, 23);
    }

    private static List<String> toolCallChunk(String id, String name, String arguments) {
        return List.of("data: {\"id\":\"tool\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"" + arguments.replace("\"", "\\\"") + "\"}}]},\"finish_reason\":\"tool_calls\"}]}" );
    }

    private static List<String> textChunks(String... chunks) {
        return java.util.Arrays.stream(chunks)
                .map(chunk -> "data: {\"id\":\"text\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"" + chunk + "\"},\"finish_reason\":null}]}")
                .toList();
    }

    interface Assistant {
        TokenStream chat(@MemoryId String conversationId, @UserMessage String message);
    }

    static final class CourseTools {
        @Tool("查询课程目录")
        String outline(@P("课程ID") int courseId) {
            if (courseId != 7) {
                throw new IllegalArgumentException("未授权课程");
            }
            return "Redis、Lua、缓存击穿";
        }
    }

    static final class GuardedStreamingChatModel implements StreamingChatModel {
        private final StreamingChatModel delegate;
        private final RoundGuard guard;

        GuardedStreamingChatModel(StreamingChatModel delegate, RoundGuard guard) {
            this.delegate = delegate;
            this.guard = guard;
        }

        @Override
        public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            guard.beforeModelRound(request);
            delegate.chat(request, handler);
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return delegate.defaultRequestParameters();
        }
    }

    static final class RoundGuard {
        private final int maxAllowed;
        private final AtomicInteger invocations = new AtomicInteger();

        RoundGuard(int maxAllowed) {
            this.maxAllowed = maxAllowed;
        }

        void beforeModelRound(ChatRequest request) {
            int current = invocations.incrementAndGet();
            if (request.messages().isEmpty() || current > maxAllowed) {
                throw new GuardRejectedException("模型回合预算已用尽: " + current);
            }
        }

        int invocations() {
            return invocations.get();
        }
    }

    static final class GuardRejectedException extends RuntimeException {
        GuardRejectedException(String message) {
            super(message);
        }
    }

    record StreamResult(String text, Throwable error) { }
}
