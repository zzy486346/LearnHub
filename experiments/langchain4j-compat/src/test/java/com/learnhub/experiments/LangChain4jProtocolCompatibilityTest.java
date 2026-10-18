package com.learnhub.experiments;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangChain4jProtocolCompatibilityTest {

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
    void parsesSynchronousContentAndUsageWithoutRequestLogging() {
        server.enqueue(jsonResponse("""
                {"id":"chatcmpl-sync","object":"chat.completion","created":1,"model":"mock-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"同步答案"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
                """));

        OpenAiChatModel model = OpenAiChatModel.builder()
                .baseUrl(baseUrl())
                .apiKey("local-mock-key")
                .modelName("mock-model")
                .logRequests(false)
                .logResponses(false)
                .maxRetries(0)
                .build();

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from("问题"))
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("同步答案");
        assertThat(response.tokenUsage().inputTokenCount()).isEqualTo(11);
        assertThat(response.tokenUsage().outputTokenCount()).isEqualTo(7);
        assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(18);
    }

    @Test
    void streamsRealDeltasAndKeepsRepeatedCompleteToolCallIdStable() throws Exception {
        String body = String.join("\n\n",
                "data: {\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"真\"},\"finish_reason\":null}]}",
                "data: {\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"流\"},\"finish_reason\":null}]}",
                "data: {\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_same\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{\\\"q\\\":\"}}]},\"finish_reason\":null}]}",
                "data: {\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_same\",\"type\":\"function\",\"function\":{\"arguments\":\"7}\"}}]},\"finish_reason\":null}]}",
                "data: {\"id\":\"chatcmpl-stream\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}",
                "data: [DONE]", "");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body, 19));

        OpenAiStreamingChatModel model = streamingModel(Duration.ofSeconds(2));
        List<String> deltas = new ArrayList<>();
        AtomicReference<ChatResponse> completed = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        model.chat(ChatRequest.builder().messages(UserMessage.from("调用工具")).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        deltas.add(partialResponse);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        completed.set(completeResponse);
                        done.countDown();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        error.set(throwable);
                        done.countDown();
                    }
                });

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        assertThat(deltas).containsExactly("真", "流");
        assertThat(completed.get().aiMessage().toolExecutionRequests()).singleElement()
                .satisfies(tool -> {
                    assertThat(tool.id()).isEqualTo("call_same");
                    assertThat(tool.name()).isEqualTo("lookup");
                    assertThat(tool.arguments()).isEqualTo("{\"q\":7}");
                });
        assertThat(completed.get().tokenUsage().totalTokenCount()).isEqualTo(5);
    }

    @Test
    void exposesProviderErrorAndTimeout() {
        server.enqueue(jsonResponse("{\"error\":{\"message\":\"mock quota exhausted\",\"type\":\"quota\"}}")
                .setResponseCode(429));
        OpenAiChatModel providerErrorModel = syncModel(Duration.ofSeconds(1));

        assertThatThrownBy(() -> providerErrorModel.chat("问题"))
                .hasMessageContaining("mock quota exhausted");

        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        OpenAiChatModel timeoutModel = syncModel(Duration.ofMillis(150));

        assertThatThrownBy(() -> timeoutModel.chat("问题"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void cancellationStopsCallbacksAfterCurrentChunk() throws Exception {
        String body = String.join("\n\n",
                "data: {\"id\":\"cancel\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"first\"},\"finish_reason\":null}]}",
                "data: {\"id\":\"cancel\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"late\"},\"finish_reason\":null}]}",
                "data: [DONE]", "");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(body, 180)
                .throttleBody(180, 300, TimeUnit.MILLISECONDS));

        OpenAiStreamingChatModel model = streamingModel(Duration.ofSeconds(2));
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch first = new CountDownLatch(1);

        model.chat(ChatRequest.builder().messages(UserMessage.from("取消")).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(dev.langchain4j.model.chat.response.PartialResponse partial,
                                                  dev.langchain4j.model.chat.response.PartialResponseContext context) {
                        callbacks.incrementAndGet();
                        context.streamingHandle().cancel();
                        first.countDown();
                    }

                    @Override public void onCompleteResponse(ChatResponse completeResponse) { }
                    @Override public void onError(Throwable throwable) { }
                });

        assertThat(first.await(2, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(700);
        assertThat(callbacks).hasValue(1);
    }

    @Test
    void reportsAnErrorWhenProviderDisconnectsDuringStreaming() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":\"broken\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"mock-model\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        streamingModel(Duration.ofSeconds(2)).chat("断流", new StreamingChatResponseHandler() {
            @Override public void onCompleteResponse(ChatResponse completeResponse) { done.countDown(); }
            @Override public void onError(Throwable throwable) { error.set(throwable); done.countDown(); }
        });

        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNotNull();
    }

    private OpenAiChatModel syncModel(Duration timeout) {
        return OpenAiChatModel.builder().baseUrl(baseUrl()).apiKey("local-mock-key")
                .modelName("mock-model").timeout(timeout).maxRetries(0)
                .logRequests(false).logResponses(false).build();
    }

    private OpenAiStreamingChatModel streamingModel(Duration timeout) {
        return OpenAiStreamingChatModel.builder().baseUrl(baseUrl()).apiKey("local-mock-key")
                .modelName("mock-model").timeout(timeout)
                .accumulateToolCallId(false).logRequests(false).logResponses(false).build();
    }

    private String baseUrl() {
        return server.url("/v1").toString();
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
