package com.learnhub.assistant.chat.model;

import static org.assertj.core.api.Assertions.*;

import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ToolStreamingSessionTest {
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final AssistantIoScope io = new AssistantIoScope(Duration.ofSeconds(5));
    private final AtomicInteger requests = new AtomicInteger();
    private final ToolSpecification specification = ToolSpecification.builder().name("outline")
            .description("测试课程目录").parameters(JsonObjectSchema.builder().build()).build();

    @BeforeEach void attach() { io.attach(); }
    @AfterEach void cleanup() throws Exception {
        io.cancel();
        io.close();
        network.shutdownNow();
        assertThat(network.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test void automaticToolsAndRoundsStayOnWorkerAndAccountRawResponsesExactlyOnce() {
        Thread worker = Thread.currentThread();
        List<String> sequence = new ArrayList<>();
        var finalResponse = text("建议先学目录第一节。");
        var first = call("a");
        var second = call("b");
        StreamingChatModel model = async((round, handler) -> {
            assertThat(Thread.currentThread()).isNotSameAs(worker);
            if (round == 1) {
                handler.onCompleteToolCall(new CompleteToolCall(0, first));
                handler.onCompleteToolCall(new CompleteToolCall(1, second));
                handler.onCompleteResponse(toolResponse(first, second));
            } else {
                handler.onPartialResponse("建议先学");
                handler.onPartialResponse("目录第一节。");
                handler.onCompleteResponse(finalResponse);
            }
        });
        List<ChatRequest> sent = new ArrayList<>();
        List<ChatResponse> rawResponses = new ArrayList<>();
        StringBuilder streamed = new StringBuilder();
        var result = ToolStreamingSession.run(model, memory(), Map.of(specification, (request, id) -> {
            assertThat(Thread.currentThread()).isSameAs(worker);
            assertThat(AssistantIoScope.current()).isSameAs(io);
            sequence.add(request.id());
            return "受控目录";
        }), "学习建议", request -> { sent.add(request); return request; }, response -> {
            assertThat(Thread.currentThread()).isSameAs(worker);
            rawResponses.add(response);
            sequence.add("validated");
        }, streamed::append, () -> assertThat(Thread.currentThread()).isSameAs(worker), io, 3);

        assertThat(sequence).containsExactly("validated", "a", "b", "validated");
        assertThat(requests).hasValue(2);
        assertThat(sent.get(1).messages()).filteredOn(ToolExecutionResultMessage.class::isInstance).hasSize(2);
        assertThat(rawResponses).hasSize(2);
        assertThat(result.finalResponse()).isSameAs(finalResponse);
        assertThat(result.finalResponse().tokenUsage().totalTokenCount()).isEqualTo(5);
        assertThat(result.text()).isEqualTo(streamed.toString()).isEqualTo("建议先学目录第一节。");
        assertThat(AssistantIoScope.current()).isSameAs(io);
        assertThat(io.remaining()).isPositive();
    }

    @Test void secondRoundBudgetRejectionPreventsSecondNetworkRequest() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        var budget = new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "测试预算耗尽");
        var model = async((round, handler) -> handler.onCompleteResponse(toolResponse(call("a"))));
        assertThatThrownBy(() -> ToolStreamingSession.run(model, memory(),
                Map.of(specification, (request, id) -> { executions.incrementAndGet(); return "目录"; }),
                "目录", request -> { if (attempts.incrementAndGet() == 2) throw budget; return request; },
                response -> { }, delta -> { }, () -> { }, io, 3)).isSameAs(budget);
        assertThat(requests).hasValue(1);
        assertThat(executions).hasValue(1);
    }

    @Test void firstPartialIsImmediateAndLaterPartialsBatchWithoutLosingCompleteBoundary() {
        var firstVisible = new CountDownLatch(1);
        List<String> deltas = new ArrayList<>();
        AtomicInteger checkpoints = new AtomicInteger();
        var model = async((round, handler) -> {
            handler.onPartialResponse("首段");
            try { assertThat(firstVisible.await(2, TimeUnit.SECONDS)).isTrue(); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            for (int i = 0; i < 40; i++) handler.onPartialResponse("续");
            handler.onCompleteResponse(text("首段" + "续".repeat(40)));
        });
        var result = ToolStreamingSession.run(model, memory(), Map.of(), "问题", request -> request,
                response -> { }, delta -> { deltas.add(delta); firstVisible.countDown(); },
                checkpoints::incrementAndGet, io, 3);
        assertThat(deltas).hasSize(2);
        assertThat(deltas.get(0)).isEqualTo("首段");
        assertThat(String.join("", deltas)).isEqualTo(result.text()).isEqualTo("首段" + "续".repeat(40));
        assertThat(checkpoints.get()).isLessThan(25);
    }

    @Test void invalidCompleteBatchCannotExecuteEarlyCompleteToolCall() {
        AtomicInteger executions = new AtomicInteger();
        var invalid = new BusinessException("ASSISTANT_TOOL_INVALID", "重复编号");
        var model = async((round, handler) -> {
            handler.onCompleteToolCall(new CompleteToolCall(0, call("a")));
            handler.onCompleteResponse(toolResponse(call("a"), call("a")));
        });
        assertThatThrownBy(() -> ToolStreamingSession.run(model, memory(),
                Map.of(specification, (request, id) -> { executions.incrementAndGet(); return "目录"; }),
                "目录", request -> request, response -> { throw invalid; }, delta -> { }, () -> { }, io, 3))
                .isSameAs(invalid);
        assertThat(executions).hasValue(0);
        assertThat(requests).hasValue(1);
    }

    @Test void cancellationAfterFirstDeltaSuppressesQueuedTextAndFinal() {
        List<String> received = new ArrayList<>();
        var model = async((round, handler) -> {
            handler.onPartialResponse("第一段");
            handler.onPartialResponse("禁止迟到");
            handler.onCompleteResponse(text("第一段禁止迟到"));
        });
        assertThatThrownBy(() -> ToolStreamingSession.run(model, memory(), Map.of(), "问题", request -> request,
                response -> fail("取消后不得接收完整响应"), delta -> { received.add(delta); io.cancel(); },
                () -> { }, io, 3)).isInstanceOf(BusinessException.class).hasMessageContaining("取消");
        assertThat(received).containsExactly("第一段");
    }

    @Test void providerFailureTerminatesWithoutSwallowingBusinessException() {
        var failure = new BusinessException("AI_MODEL_UNAVAILABLE", "测试模型失败");
        var model = async((round, handler) -> handler.onError(failure));
        assertThatThrownBy(() -> ToolStreamingSession.run(model, memory(), Map.of(), "问题", request -> request,
                response -> { }, delta -> { }, () -> { }, io, 3)).isSameAs(failure);
    }

    @Test void toolFailureStopsBeforeAnotherModelRound() {
        var failure = new BusinessException("TOOL_UNAVAILABLE", "测试工具失败");
        var model = async((round, handler) -> handler.onCompleteResponse(toolResponse(call("a"))));
        assertThatThrownBy(() -> ToolStreamingSession.run(model, memory(),
                Map.of(specification, (request, id) -> { throw failure; }), "目录", request -> request,
                response -> { }, delta -> { }, () -> { }, io, 3)).isSameAs(failure);
        assertThat(requests).hasValue(1);
    }

    @Test void finalTextMustEqualActualStreamedText() {
        var model = async((round, handler) -> {
            handler.onPartialResponse("草稿");
            handler.onCompleteResponse(text("不同的最终正文"));
        });
        assertThatThrownBy(() -> ToolStreamingSession.run(model, memory(), Map.of(), "问题", request -> request,
                response -> { }, delta -> { }, () -> { }, io, 3))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不一致");
    }

    @Test void rawJsonCanExceedBodyLimitButMustFitProtocolLimit() {
        String rawJson = "{\"answerSegments\":[{\"text\":\"" + "字".repeat(4_100) + "\"}]}";
        var model = async((round, handler) -> {
            handler.onPartialResponse(rawJson);
            handler.onCompleteResponse(text(rawJson));
        });
        var result = ToolStreamingSession.run(model, memory(), Map.of(), "问题", request -> request,
                response -> { }, delta -> { }, () -> { }, io, 3);
        assertThat(result.text()).isEqualTo(rawJson);
    }

    @Test void rawProtocolLimitRejectsUnboundedJson() {
        var model = async((round, handler) -> handler.onPartialResponse("字".repeat(16_001)));
        assertThatThrownBy(() -> ToolStreamingSession.run(model, memory(), Map.of(), "问题", request -> request,
                response -> { }, delta -> { }, () -> { }, io, 3))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不一致");
    }

    @Test void queueOverflowCancelsWithoutUnboundedBuffering() {
        StreamingChatModel synchronous = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                for (int i = 0; i < 100; i++) handler.onPartialResponse("片段");
            }
        };
        assertThatThrownBy(() -> ToolStreamingSession.run(synchronous, memory(), Map.of(), "问题", request -> request,
                response -> { }, delta -> { }, () -> { }, io, 3))
                .isInstanceOf(BusinessException.class).hasMessageContaining("取消");
    }

    private StreamingChatModel async(BiConsumer<Integer, StreamingChatResponseHandler> script) {
        return new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                int round = requests.incrementAndGet();
                network.execute(() -> {
                    try { script.accept(round, handler); }
                    catch (Throwable failure) { handler.onError(failure); }
                });
            }
        };
    }

    private static MessageWindowChatMemory memory() { return MessageWindowChatMemory.withMaxMessages(20); }
    private static ToolExecutionRequest call(String id) {
        return ToolExecutionRequest.builder().id(id).name("outline").arguments("{}").build();
    }
    private static ChatResponse toolResponse(ToolExecutionRequest... calls) {
        return ChatResponse.builder().aiMessage(AiMessage.from(calls)).tokenUsage(new TokenUsage(3, 2, 5))
                .finishReason(FinishReason.TOOL_EXECUTION).build();
    }
    private static ChatResponse text(String text) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).tokenUsage(new TokenUsage(3, 2, 5))
                .finishReason(FinishReason.STOP).build();
    }
}
