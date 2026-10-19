package com.learnhub.assistant.chat.application;

import com.learnhub.assistant.model.AiProperties;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ChatConversationService {
    private final ChatPersistenceService persistence;
    private final ChatHistoryService history;
    private final CourseService courses;
    private final AiProperties properties;

    public ChatConversationService(ChatPersistenceService persistence, ChatHistoryService history,
                                   CourseService courses, AiProperties properties) {
        this.persistence = persistence;
        this.history = history;
        this.courses = courses;
        this.properties = properties;
    }

    public ChatPersistenceService.Conversation create(long owner, long courseId, String title) {
        courses.requireAvailable(courseId, owner);
        return persistence.createConversation(owner, courseId, title);
    }

    public ChatPersistenceService.Started start(long owner, String conversationId, String requestId,
                                                String message, Duration deadline) {
        history.require(owner, conversationId);
        return persistence.start(owner, conversationId, requestId, message, deadline);
    }

    /** 阶段1仅开放明确标注的Fake；真实模式在流式和取消保护完成后接入。 */
    public String prepareFake(long owner, ChatPersistenceService.Run stored,
                                                   AssistantRunGuard.Run guard) {
        try {
            if (properties.resolvedMode() != AiProperties.Mode.FAKE)
                throw new BusinessException("AI_DISABLED", "独立会话暂仅开放模拟模式，真实模型尚待流式验收");
            guard.check();
            var conversation = history.require(owner, stored.conversationId());
            var previous = history.messages(owner, stored.conversationId(), Long.MAX_VALUE, 20).stream()
                    .filter(message -> !message.restricted() && "USER".equals(message.role())
                            && !stored.id().equals(message.runId())).reduce((a, b) -> b);
            String response = "【模拟回答】收到你的问题：" + stored.message()
                    + previous.map(message -> "\n上一次问题是：" + message.content()).orElse("")
                    + "\n这是会话与协议演示，未调用真实模型。";
            int input = stored.message().getBytes(StandardCharsets.UTF_8).length
                    + previous.map(message -> message.content().getBytes(StandardCharsets.UTF_8).length).orElse(0) + 1024;
            guard.reserveModel(input, Math.min(512, properties.getMaxOutputTokens()));
            history.require(owner, conversation.id());
            guard.check();
            guard.recordUsage(0, 0, 0);
            return response;
        } catch (RuntimeException failure) {
            persistence.fail(owner, stored.id(), "FAILED",
                    failure instanceof BusinessException business ? business.getCode() : "ASSISTANT_UNAVAILABLE");
            throw failure;
        }
    }

    public ChatPersistenceService.Run commitFake(long owner, ChatPersistenceService.Run stored, String response) {
        return commitAnswer(owner, stored, response, "[]", "{}");
    }

    public ChatPersistenceService.Run commitAnswer(long owner, ChatPersistenceService.Run stored, String response,
                                                  String sourcesJson, String usageJson) {
        var conversation = history.require(owner, stored.conversationId());
        if (!"[]".equals(sourcesJson)) {
            var checked = history.verify(owner, conversation.courseId(), new ChatPersistenceService.Message(
                    "pending", 0, "ASSISTANT", response, "COMPLETED", stored.id(), sourcesJson));
            if (checked.restricted()) throw new BusinessException("ASSISTANT_EVIDENCE_CHANGED", "资料或权限已变化，本次回答已废弃");
        }
        if (!persistence.complete(owner, stored.id(), stored.leaseOwner(), response, sourcesJson, usageJson))
            throw new BusinessException("ASSISTANT_CANCELLED", "本次会话运行已终止");
        return new ChatPersistenceService.Run(stored.id(), stored.conversationId(), stored.ownerUserId(),
                stored.clientRequestId(), "COMPLETED", null, stored.message());
    }

    public List<ChatPersistenceService.Conversation> list(long owner, int offset, int limit) {
        return persistence.conversations(owner, offset, limit);
    }

    /** 来源真正发送前重新获取当前授权的引用，提交终态不受本次发送失败影响。 */
    public List<EvidenceBook.Citation> confirmedSources(long owner, ChatPersistenceService.Run stored,
                                                      String response, String sourcesJson) {
        var conversation = history.require(owner, stored.conversationId());
        var checked = history.verify(owner, conversation.courseId(), new ChatPersistenceService.Message(
                "confirmed", 0, "ASSISTANT", response, "COMPLETED", stored.id(), sourcesJson));
        if (checked.restricted()) throw new BusinessException("ASSISTANT_EVIDENCE_CHANGED", "资料或权限已变化，本次回答已废弃");
        return checked.sources();
    }
}
