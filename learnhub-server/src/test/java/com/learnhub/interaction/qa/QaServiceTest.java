package com.learnhub.interaction.qa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QaServiceTest {
    @Test
    void createsQuestionAndAddsAnswer() {
        QaService service = new QaService();
        Question question = service.create(7L, new QaRequests.CreateQuestion(3L, "JWT", "如何续期？"));

        Question answered = service.answer(8L, question.id(), new QaRequests.CreateAnswer("使用刷新令牌。"));

        assertEquals(1, answered.answers().size());
        assertEquals("使用刷新令牌。", answered.answers().get(0).content());
        assertEquals(1, service.list(3L).size());
    }
}
