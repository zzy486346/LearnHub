package com.learnhub.interaction;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import com.learnhub.interaction.like.LikeTargetType;
import com.learnhub.interaction.qa.AnswerEntity;
import com.learnhub.interaction.qa.AnswerMapper;
import com.learnhub.interaction.qa.QuestionEntity;
import com.learnhub.interaction.qa.QuestionMapper;
import com.learnhub.order.CourseOrderService;
import org.springframework.stereotype.Service;

@Service
public class InteractionTargetService {
    private final CourseMapper courses;
    private final QuestionMapper questions;
    private final AnswerMapper answers;
    private final CourseOrderService orders;

    public InteractionTargetService(CourseMapper courses, QuestionMapper questions,
                                    AnswerMapper answers, CourseOrderService orders) {
        this.courses = courses;
        this.questions = questions;
        this.answers = answers;
        this.orders = orders;
    }

    public void requireVisible(Long userId, LikeTargetType type, Long targetId) {
        if (type == null || targetId == null || targetId <= 0) throw unavailable();
        switch (type) {
            case COURSE -> {
                Course course = courses.selectOne(new QueryWrapper<Course>()
                        .eq("id", targetId).eq("deleted", 0));
                if (course == null || !("PUBLISHED".equals(course.getStatus())
                        || ("OFFLINE".equals(course.getStatus()) && orders.hasPurchased(userId, targetId)))) {
                    throw unavailable();
                }
            }
            case QUESTION -> requireQuestion(targetId);
            case ANSWER -> {
                AnswerEntity answer = answers.selectById(targetId);
                if (answer == null || Integer.valueOf(1).equals(answer.getDeleted())
                        || !"VISIBLE".equals(answer.getStatus())) throw unavailable();
                requireQuestion(answer.getQuestionId());
            }
        }
    }

    private void requireQuestion(Long id) {
        QuestionEntity question = id == null ? null : questions.selectById(id);
        if (question == null || Integer.valueOf(1).equals(question.getDeleted())
                || !"OPEN".equals(question.getStatus())) throw unavailable();
    }

    private BusinessException unavailable() {
        return new BusinessException("INTERACTION_TARGET_UNAVAILABLE", "目标不存在或当前不可见");
    }
}
