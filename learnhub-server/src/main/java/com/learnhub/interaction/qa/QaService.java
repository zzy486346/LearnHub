package com.learnhub.interaction.qa;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class QaService {
    private final AtomicLong questionIds = new AtomicLong();
    private final AtomicLong answerIds = new AtomicLong();
    private final ConcurrentHashMap<Long, Question> questions = new ConcurrentHashMap<>();

    public Question create(Long userId, QaRequests.CreateQuestion request) {
        long id = questionIds.incrementAndGet();
        Question question = new Question(id, userId, request.courseId(), request.title(), request.content(),
                Instant.now(), List.of());
        questions.put(id, question);
        return question;
    }

    public List<Question> list(Long courseId) {
        return questions.values().stream()
                .filter(question -> courseId == null || courseId.equals(question.courseId()))
                .sorted(Comparator.comparing(Question::createdAt).reversed())
                .toList();
    }

    public Question answer(Long userId, Long questionId, QaRequests.CreateAnswer request) {
        return questions.compute(questionId, (id, question) -> {
            if (question == null) throw new IllegalArgumentException("Question not found: " + questionId);
            List<Answer> answers = new ArrayList<>(question.answers());
            answers.add(new Answer(answerIds.incrementAndGet(), userId, request.content(), Instant.now()));
            return new Question(question.id(), question.userId(), question.courseId(), question.title(),
                    question.content(), question.createdAt(), List.copyOf(answers));
        });
    }
}
