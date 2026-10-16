package com.learnhub.assistant.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.model.*;
import com.learnhub.assistant.runtime.*;
import com.learnhub.common.exception.BusinessException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AssistantModelInvoker {
    private static final Logger log = LoggerFactory.getLogger(AssistantModelInvoker.class);
    private final ChatModelClient model;
    private final ObjectMapper mapper;
    private final AssistantLimits limits;
    private final AiProperties properties;
    public AssistantModelInvoker(ChatModelClient model, ObjectMapper mapper, AssistantLimits limits, AiProperties properties) {
        this.model = model; this.mapper = mapper; this.limits = limits; this.properties = properties;
    }
    public String configuredMode() { return properties.resolvedMode().name(); }

    public ChatResponse call(List<ChatMessage> messages, List<Map<String, Object>> tools, EvidenceBook book, AssistantRunGuard.Run run) {
        run.check(); book.check(); run.check();
        int bytes;
        try {
            // 包括重复历史与工具 schema；UTF-8 字节加协议余量是保守估算，不能宣称精确 tokenizer。
            bytes = Math.addExact(mapper.writeValueAsBytes(Map.of("messages", messages, "tools", tools)).length, 1024);
        } catch (Exception error) { throw new BusinessException("ASSISTANT_INPUT_INVALID", "无法组织模型上下文"); }
        if (bytes > limits.getMaxInputBytes())
            throw new BusinessException("ASSISTANT_BUDGET_EXCEEDED", "本轮上下文超过输入字节预算");
        int output = Math.min(limits.getMaxOutputTokens(), properties.getMaxOutputTokens());
        run.reserveModel(bytes, output);
        // 预留可能访问 Redis；等待期间发生撤权时，不能把刚才的旧证据发出。
        book.check(); run.check();
        var response = model.generate(new ChatRequest(messages, tools, output, run.remaining(), true));
        var usage = response.usage();
        run.recordUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens());
        run.check();
        log.info("Assistant model completed requestId={} promptVersion={} model={} mode={} usage={} reserved={}",
                run.requestId(), GroundedAnswers.PROMPT_VERSION, properties.getModel(), response.modelMode(),
                usage.totalTokens(), run.summary().reservedTokens());
        return response;
    }
}
