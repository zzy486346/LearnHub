package com.learnhub.assistant.chat.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.qa.CourseQaService;
import com.learnhub.assistant.qa.AnswerEvidenceOutcome;
import com.learnhub.assistant.qa.EvidenceBook;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 正式历史与可发送上下文共用证据复核，数据库原始来源不直接暴露给浏览器。 */
@Service
public class ChatHistoryService {
    private final ChatPersistenceService persistence;
    private final CourseService courses;
    private final CourseQaService qa;
    private final ChatEvidenceCodec evidence;

    public ChatHistoryService(ChatPersistenceService persistence, CourseService courses,
                              CourseQaService qa, ObjectMapper mapper) {
        this(persistence, courses, qa, mapper, ChatEvidenceCodec.legacyOnly());
    }

    @Autowired
    public ChatHistoryService(ChatPersistenceService persistence, CourseService courses,
                              CourseQaService qa, ObjectMapper mapper, ChatEvidenceCodec evidence) {
        this.persistence = persistence;
        this.courses = courses;
        this.qa = qa;
        this.evidence = evidence;
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
            var book = qa.evidenceBook(owner, courseId);
            evidence.restoreInto(owner, courseId, message.sourcesJson(), book);
            // 依赖仍需复核，但弱召回不是资料不足文案的支持来源；历史页与 SSE 来源共用此出口。
            if (AnswerEvidenceOutcome.INSUFFICIENT_TEXT.equals(message.content()))
                return view(message, message.content(), List.of(), false);
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
