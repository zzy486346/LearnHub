package com.learnhub.interaction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.interaction.like.LikeTargetType;
import com.learnhub.interaction.qa.AnswerEntity;
import com.learnhub.interaction.qa.AnswerMapper;
import com.learnhub.interaction.qa.QuestionEntity;
import com.learnhub.interaction.qa.QuestionMapper;
import com.learnhub.order.CourseOrderService;
import org.junit.jupiter.api.Test;

class InteractionTargetServiceTest {
    private final CourseMapper courses = mock(CourseMapper.class);
    private final QuestionMapper questions = mock(QuestionMapper.class);
    private final AnswerMapper answers = mock(AnswerMapper.class);
    private final CourseOrderService orders = mock(CourseOrderService.class);
    private final InteractionTargetService service = new InteractionTargetService(courses, questions, answers, orders);

    @Test
    void missingOrInvalidTargetIsRejected() {
        assertThatThrownBy(() -> service.requireVisible(7L, LikeTargetType.COURSE, 1L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireVisible(7L, LikeTargetType.QUESTION, 0L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.requireVisible(7L, null, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void publishedCourseIsVisibleButDraftIsNot() {
        Course course = new Course();
        course.setStatus("PUBLISHED");
        when(courses.selectOne(any())).thenReturn(course);
        service.requireVisible(7L, LikeTargetType.COURSE, 1L);
        course.setStatus("DRAFT");
        assertThatThrownBy(() -> service.requireVisible(7L, LikeTargetType.COURSE, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void offlineCourseIsVisibleOnlyToPurchasedUser() {
        Course course = new Course();
        course.setStatus("OFFLINE");
        when(courses.selectOne(any())).thenReturn(course);
        when(orders.hasPurchased(7L, 1L)).thenReturn(true);
        service.requireVisible(7L, LikeTargetType.COURSE, 1L);
        assertThatThrownBy(() -> service.requireVisible(8L, LikeTargetType.COURSE, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void closedOrDeletedQuestionIsRejected() {
        QuestionEntity question = new QuestionEntity();
        question.setStatus("OPEN");
        when(questions.selectById(1L)).thenReturn(question);
        service.requireVisible(7L, LikeTargetType.QUESTION, 1L);
        question.setStatus("CLOSED");
        assertThatThrownBy(() -> service.requireVisible(7L, LikeTargetType.QUESTION, 1L))
                .isInstanceOf(BusinessException.class);
        question.setStatus("OPEN");
        question.setDeleted(1);
        assertThatThrownBy(() -> service.requireVisible(7L, LikeTargetType.QUESTION, 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void answerRequiresVisibleUndeletedParentAndOwnVisibility() {
        AnswerEntity answer = new AnswerEntity();
        answer.setQuestionId(1L);
        answer.setStatus("VISIBLE");
        when(answers.selectById(2L)).thenReturn(answer);
        assertThatThrownBy(() -> service.requireVisible(7L, LikeTargetType.ANSWER, 2L))
                .isInstanceOf(BusinessException.class);
        QuestionEntity question = new QuestionEntity();
        question.setStatus("OPEN");
        when(questions.selectById(1L)).thenReturn(question);
        service.requireVisible(7L, LikeTargetType.ANSWER, 2L);
        answer.setDeleted(1);
        assertThatThrownBy(() -> service.requireVisible(7L, LikeTargetType.ANSWER, 2L))
                .isInstanceOf(BusinessException.class);
        answer.setDeleted(0);
        answer.setStatus("HIDDEN");
        assertThatThrownBy(() -> service.requireVisible(7L, LikeTargetType.ANSWER, 2L))
                .isInstanceOf(BusinessException.class);
    }
}
