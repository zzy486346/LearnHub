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

        var pending = new EventQueue(io);
        var state = new State();
        Runnable check = () -> { pending.checkFailure(); io.remaining(); checkpoint.run(); pending.checkFailure(); io.remaining(); };
        StreamingChatModel guarded = new StreamingChatModel() {
            @Override public ChatRequestParameters defaultRequestParameters() {
                return delegate.defaultRequestParameters();
            }

            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                check.run();
                ChatRequest checked = Objects.requireNonNull(beforeRound.apply(request));
                check.run();
                var firstPartial = new AtomicBoolean(true);
                delegate.chat(checked, new StreamingChatResponseHandler() {
                    @Override public void onPartialResponse(String text) {
                        pending.add(new Partial(handler, text, firstPartial.getAndSet(false)));
                    }

                    // 顺序模式在完整响应中执行工具；绝不转发可能提前执行工具的分片事件。
                    @Override public void onCompleteResponse(ChatResponse response) {
                        pending.add(new Action(() -> {
                            afterRound.accept(response);
                            check.run();
                            state.lastRawResponse = response;
                            handler.onCompleteResponse(response);
                        }));
                    }

                    @Override public void onError(Throwable error) {
                        pending.add(new Action(() -> handler.onError(error)));
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
            // 回调可能在检查与remaining()之间失败；退出时也保留原始缓冲错误。
            if (failure instanceof RuntimeException) pending.checkFailure();
            throw failure;
        } finally {
            pending.close();
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
    private sealed interface Event permits Partial, BufferedPartial, Action { }
    private record Partial(StreamingChatResponseHandler handler, String text, boolean first) implements Event { }
    private record Action(Runnable callback) implements Event { }

    /** 在网络回调侧合并相邻分片；总正文仍限16000字符，控制事件队列仍限64条。 */
    private static final class EventQueue {
        private final ArrayBlockingQueue<Event> events = new ArrayBlockingQueue<>(64);
        private final AssistantIoScope io;
        private volatile RuntimeException failure;
        private boolean closed;
        private BufferedPartial tail;
        private int receivedChars;

        EventQueue(AssistantIoScope io) { this.io = io; }

        void add(Event event) {
            synchronized (this) {
                if (closed || failure != null) return;
                Event queued = event;
                if (event instanceof Partial partial) {
                    if (partial.text() == null || partial.text().length() > 16_000 - receivedChars) {
                        failure = invalid();
                    } else {
                        receivedChars += partial.text().length();
                        if (!partial.first() && tail != null && tail.handler == partial.handler()) {
                            tail.text.append(partial.text());
                            return;
                        }
                        // 首分片单独保留，避免合并推迟首次展示或吞掉首分片后的取消边界。
                        tail = partial.first() ? null : new BufferedPartial(partial.handler(), partial.text());
                        queued = tail == null ? partial : tail;
                    }
                } else {
                    // 完成/错误事件是不可跨越的边界，不能与下一回合的文本混合。
                    tail = null;
                }
                if (failure == null && !events.offer(queued))
                    failure = new BusinessException("ASSISTANT_STREAM_OVERLOADED", "模型流式事件超过本地处理缓冲上限，请重试");
                if (failure == null) return;
            }
            // 先发布原始错误，再中断下游；消费者优先报告错误，不把过载误报成用户取消。
            io.cancel();
        }

        Event poll(long timeout, TimeUnit unit) throws InterruptedException {
            Event event = events.poll(timeout, unit);
            if (event instanceof BufferedPartial partial) {
                synchronized (this) {
                    if (tail == partial) tail = null;
                    return new Partial(partial.handler, partial.text.toString(), false);
                }
            }
            return event;
        }

        void checkFailure() {
            RuntimeException error = failure;
            if (error != null) throw error;
        }

        synchronized void close() {
            closed = true;
            tail = null;
            events.clear();
        }
    }

    private static final class BufferedPartial implements Event {
        private final StreamingChatResponseHandler handler;
        private final StringBuilder text;
        BufferedPartial(StreamingChatResponseHandler handler, String text) {
            this.handler = handler;
            this.text = new StringBuilder(text);
        }
    }
    private static final class State {
        private final StringBuilder text = new StringBuilder();
        private ChatResponse lastRawResponse;
        private Throwable failure;
        private boolean done;
    }
    public record Result(String text, ChatResponse finalResponse) { }
}
