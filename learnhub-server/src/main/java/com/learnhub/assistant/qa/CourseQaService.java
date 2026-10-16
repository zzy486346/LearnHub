package com.learnhub.assistant.qa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.assistant.knowledge.KnowledgeRetrievalService;
import com.learnhub.assistant.knowledge.KnowledgeStore;
import com.learnhub.assistant.model.ChatMessage;
import com.learnhub.assistant.runtime.AssistantRunGuard;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.service.CourseService;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CourseQaService {
    private final KnowledgeRetrievalService retrieval;
    private final KnowledgeStore store;
    private final CourseService courses;
    private final ObjectMapper mapper;
    private final AssistantModelInvoker model;
    private final GroundedAnswers answers;
    public CourseQaService(KnowledgeRetrievalService retrieval, KnowledgeStore store, CourseService courses,
            ObjectMapper mapper, AssistantModelInvoker model, GroundedAnswers answers) {
        this.retrieval = retrieval; this.store = store; this.courses = courses;
        this.mapper = mapper; this.model = model; this.answers = answers;
    }
    public EvidenceBook evidenceBook(Long userId, long courseId) { return new EvidenceBook(userId, courseId, store, courses, mapper); }

    public GroundedAnswer answer(Long userId, long courseId, String question, AssistantRunGuard.Run run) {
        validate(courseId, question); run.check();
        var book = evidenceBook(userId, courseId);
        var retrieved = retrieval.retrieve(userId, courseId, question, 5);
        retrieved.sources().forEach(book::material);
        if (retrieved.sources().isEmpty()) return answers.insufficient(model.configuredMode(), book, run);
        String input;
        try { input = mapper.writeValueAsString(Map.of("question", question, "evidence", book.context())); }
        catch (Exception error) { throw GroundedAnswers.invalid(); }
        var response = model.call(List.of(new ChatMessage("system", GroundedAnswers.SYSTEM, null, List.of()),
                new ChatMessage("user", input, null, List.of())), List.of(), book, run);
        if (!response.toolCalls().isEmpty() || !"stop".equals(response.finishReason())) throw GroundedAnswers.invalid();
        return answers.finish(response.text(), response.modelMode(), book, run, List.of());
    }

    public static void validate(long courseId, String text) {
        if (courseId <= 0 || text == null || text.isBlank() || text.length() > 500)
            throw new BusinessException("ASSISTANT_INPUT_INVALID", "课程编号须为正数，问题须为1至500字");
    }
}
