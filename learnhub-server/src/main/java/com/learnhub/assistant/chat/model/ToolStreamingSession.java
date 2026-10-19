package com.learnhub.assistant.chat.model;

import com.learnhub.assistant.runtime.AssistantIoScope;
import com.learnhub.common.exception.BusinessException;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolArgumentsErrorHandler;
import dev.langchain4j.service.tool.ToolExecutionErrorHandler;
import dev.langchain4j.service.tool.ToolExecutor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/** SDK 回调先排队，再由持有原 IO scope 的助理工作线程执行工具和下一轮请求。 */
public final class ToolStreamingSession {
    private ToolStreamingSession() { }

    public static Result run(StreamingChatModel delegate, ChatMemory memory,
            Map<ToolSpecification, ToolExecutor> tools, String question,
            UnaryOperator<ChatRequest> beforeRound, Consumer<ChatResponse> afterRound,
            Consumer<String> delta, Runnable checkpoint, AssistantIoScope io, int maxTrips) {
        Objects.requireNonNull(delegate);
        Objects.requireNonNull(memory);
        Objects.requireNonNull(tools);
        Objects.requireNonNull(question);
        Objects.requireNonNull(beforeRound);
        Objects.requireNonNull(afterRound);
        Objects.requireNonNull(delta);
        Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(io);
        if (maxTrips < 1 || maxTrips > 10 || AssistantIoScope.current() != io)
            throw new IllegalArgumentException("invalid tool streaming scope or round limit");

        var pending = new ArrayBlockingQueue<Event>(64);
        var closed = new AtomicBoolean();
        var state = new State();
        Runnable check = () -> { io.remaining(); checkpoint.run(); io.remaining(); };
        Consumer<Event> enqueue = event -> {
            if (!closed.get() && !pending.offer(event)) io.cancel();
        };
        StreamingChatModel guarded = new StreamingChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() {
                return delegate.defaultRequestParameters();
            }

            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                check.run();
                ChatRequest checked = Objects.requireNonNull(beforeRound.apply(request));
                check.run();
                delegate.chat(checked, new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String text) {
                        enqueue.accept(new Partial(handler, text));
                    }

                    // 顺序模式在完整响应中执行工具；绝不转发可能提前执行工具的分片事件。
                    @Override public void onCompleteResponse(ChatResponse response) {
                        enqueue.accept(new Action(() -> {
                            afterRound.accept(response);
                            check.run();
                            state.lastRawResponse = response;
                            handler.onCompleteResponse(response);
                        }));
                    }

                    @Override public void onError(Throwable error) {
                        enqueue.accept(new Action(() -> handler.onError(error)));
                    }
                });
            }
        };
        Map<ToolSpecification, ToolExecutor> checkedTools = new LinkedHashMap<>();
        tools.forEach((specification, executor) -> checkedTools.put(specification, (request, memoryId) -> {
            check.run();
            String result = executor.execute(request, memoryId);
            check.run();
            return result;
        }));
        var assistant = AiServices.builder(Assistant.class)
                .streamingChatModel(guarded)
                .chatMemory(memory)
                .tools(checkedTools)
                .maxToolCallingRoundTrips(maxTrips)
                .toolArgumentsErrorHandler(ToolArgumentsErrorHandler.failInvocation())
                .toolExecutionErrorHandler(ToolExecutionErrorHandler.failInvocation())
                .build();
        try {
            check.run();
            assistant.chat(question).onPartialResponse(text -> {
                check.run();
                if (text == null || state.text.length() + text.length() > 16_000) throw invalid();
                state.text.append(text);
                delta.accept(text);
            }).onCompleteResponse(response -> state.done = true)
                    .onError(error -> state.failure = error)
                    .start();
            Event retained = null;
            StreamingChatResponseHandler lastPartialHandler = null;
            while (!state.done && state.failure == null) {
                check.run();
                Event next;
                try { next = retained == null ? pending.poll(100, TimeUnit.MILLISECONDS) : retained; }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new BusinessException("ASSISTANT_CANCELLED", "本次助理请求已取消");
                }
                retained = null;
                if (next instanceof Action action) { check.run(); action.callback().run(); }
                else if (next instanceof Partial partial) {
                    if (partial.text() == null) throw invalid();
                    var batch = new StringBuilder(partial.text());
                    boolean first = lastPartialHandler != partial.handler();
                    lastPartialHandler = partial.handler();
                    if (!first) {
                        long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
                        while (batch.length() < 256) {
                            long remaining = until - System.nanoTime();
                            if (remaining <= 0) break;
                            Event following;
                            try { following = pending.poll(remaining, TimeUnit.NANOSECONDS); }
                            catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new BusinessException("ASSISTANT_CANCELLED", "本次助理请求已取消");
                            }
                            if (following == null) break;
                            if (following instanceof Partial more && more.handler() == partial.handler()) {
                                if (more.text() == null) throw invalid();
                                batch.append(more.text());
                            } else { retained = following; break; }
                        }
                    }
                    check.run();
                    partial.handler().onPartialResponse(batch.toString());
                }
            }
            if (state.failure != null) throw propagate(state.failure);
            check.run();
            var response = state.lastRawResponse;
            if (response == null || response.aiMessage() == null || response.aiMessage().hasToolExecutionRequests()
                    || !state.text.toString().equals(response.aiMessage().text())) throw invalid();
            return new Result(state.text.toString(), response);
        } catch (RuntimeException | Error failure) {
            io.cancel();
            throw failure;
        } finally {
            closed.set(true);
            pending.clear();
        }
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException runtime) return runtime;
        if (error instanceof Error fatal) throw fatal;
        return new RuntimeException(error);
    }

    private static BusinessException invalid() {
        return new BusinessException("ASSISTANT_OUTPUT_INVALID", "模型流式正文与最终响应不一致");
    }

    interface Assistant { TokenStream chat(@UserMessage String question); }
    private sealed interface Event permits Partial, Action { }
    private record Partial(StreamingChatResponseHandler handler, String text) implements Event { }
    private record Action(Runnable callback) implements Event { }
    private static final class State {
        private final StringBuilder text = new StringBuilder();
        private ChatResponse lastRawResponse;
        private Throwable failure;
        private boolean done;
    }
    public record Result(String text, ChatResponse finalResponse) { }
}
