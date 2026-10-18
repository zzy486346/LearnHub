package com.learnhub.assistant.chat.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.List;
import org.springframework.stereotype.Service;

/** 正式历史与可发送上下文共用证据复核，数据库原始来源不直接暴露给浏览器。 */
@Service
public class ChatHistoryService {
    private final ChatPersistenceService persistence;
    private final CourseService courses;
    private final CourseQaService qa;
    private final ObjectMapper mapper;

    public ChatHistoryService(ChatPersistenceService persistence, CourseService courses,
                              CourseQaService qa, ObjectMapper mapper) {
        this.persistence = persistence;
        this.courses = courses;
        this.qa = qa;
        this.mapper = mapper;
    }

    public ChatPersistenceService.Conversation require(long owner, String conversationId) {
        var conversation = persistence.conversation(owner, conversationId);
        courses.requireAvailable(conversation.courseId(), owner);
        return conversation;
    }

    public List<HistoryMessage> messages(long owner, String conversationId, long beforeSeq, int limit) {
        var conversation = require(owner, conversationId);
        return persistence.messages(owner, conversationId, beforeSeq, limit).stream()
                .map(message -> verify(owner, conversation.courseId(), message)).toList();
    }

    public HistoryMessage verify(long owner, long courseId, ChatPersistenceService.Message message) {
        if (!"ASSISTANT".equals(message.role())) return view(message, message.content(), List.of(), false);
        if (!"COMPLETED".equals(message.status())) return view(message, "", List.of(), true);
        try {
            var sources = mapper.readValue(message.sourcesJson(),
                    new TypeReference<List<KnowledgeRetrievalService.Source>>() { });
            if (sources == null || sources.size() > 20) throw new IllegalArgumentException();
            var book = qa.evidenceBook(owner, courseId);
            sources.forEach(book::material);
            book.check();
            List<EvidenceBook.Citation> citations = book.references().stream()
                    .map(reference -> book.citation((String) reference.get("evidenceId"))).toList();
            return view(message, message.content(), citations, false);
        } catch (BusinessException changed) {
            // 基础设施故障不可当成合法证据；正文同样不返回。
            return view(message, "", List.of(), true);
        } catch (Exception malformedOrUnavailable) {
            return view(message, "", List.of(), true);
        }
    }

    private static HistoryMessage view(ChatPersistenceService.Message message, String content,
                                       List<EvidenceBook.Citation> sources, boolean restricted) {
        return new HistoryMessage(message.id(), message.seq(), message.role(), content,
                message.status(), message.runId(), sources, restricted);
    }

    public record HistoryMessage(String id, long seq, String role, String content, String status,
                                 String runId, List<EvidenceBook.Citation> sources, boolean restricted) { }
}
