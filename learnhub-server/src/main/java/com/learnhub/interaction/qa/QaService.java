package com.learnhub.interaction.qa;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import com.learnhub.course.model.Course;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class QaService {
    private static final String QUESTION_OPEN = "OPEN";
    private static final String ANSWER_VISIBLE = "VISIBLE";

    private final QuestionMapper questionMapper;
    private final AnswerMapper answerMapper;
    private final UserMapper userMapper;
    private final CourseMapper courseMapper;

    public QaService(QuestionMapper questionMapper, AnswerMapper answerMapper,
                     UserMapper userMapper, CourseMapper courseMapper) {
        this.questionMapper = questionMapper;
        this.answerMapper = answerMapper;
        this.userMapper = userMapper;
        this.courseMapper = courseMapper;
    }

    @Transactional
    public Question create(Long userId, QaRequests.CreateQuestion request) {
        if (request.courseId() != null && courseMapper.selectOne(new QueryWrapper<Course>()
                .eq("id", request.courseId())
                .eq("status", "PUBLISHED")
                .eq("deleted", 0)) == null) {
            throw new BusinessException("绑定的课程不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        QuestionEntity entity = new QuestionEntity();
        entity.setUserId(userId);
        entity.setCourseId(request.courseId());
        entity.setTitle(request.title().trim());
        entity.setContent(request.content().trim());
        entity.setStatus(QUESTION_OPEN);
        entity.setLikeCount(0L);
        entity.setAnswerCount(0);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setDeleted(0);
        questionMapper.insert(entity);
        return toQuestion(entity, nicknameOf(userId));
    }

    public Page<Question> list(long page, long size, Long courseId) {
        long safePage = Math.max(1, page);
        long safeSize = Math.min(50, Math.max(1, size));
        LambdaQueryWrapper<QuestionEntity> query = new LambdaQueryWrapper<QuestionEntity>()
                .eq(QuestionEntity::getStatus, QUESTION_OPEN)
                .eq(courseId != null, QuestionEntity::getCourseId, courseId)
                .orderByDesc(QuestionEntity::getCreatedAt)
                .orderByDesc(QuestionEntity::getId);
        Page<QuestionEntity> entityPage = questionMapper.selectPage(Page.of(safePage, safeSize), query);
        Map<Long, String> nicknames = nicknames(entityPage.getRecords().stream()
                .map(QuestionEntity::getUserId).toList());
        Page<Question> result = Page.of(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        result.setRecords(entityPage.getRecords().stream()
                .map(entity -> toQuestion(entity, nicknames.get(entity.getUserId())))
                .toList());
        return result;
    }

    public Question detail(Long questionId) {
        QuestionEntity entity = requireOpenQuestion(questionId);
        return toQuestion(entity, nicknameOf(entity.getUserId()));
    }

    public Page<Answer> listAnswers(Long questionId, long page, long size) {
        requireOpenQuestion(questionId);
        long safePage = Math.max(1, page);
        long safeSize = Math.min(50, Math.max(1, size));
        LambdaQueryWrapper<AnswerEntity> query = new LambdaQueryWrapper<AnswerEntity>()
                .eq(AnswerEntity::getQuestionId, questionId)
                .eq(AnswerEntity::getStatus, ANSWER_VISIBLE)
                .orderByDesc(AnswerEntity::getAccepted)
                .orderByAsc(AnswerEntity::getCreatedAt)
                .orderByAsc(AnswerEntity::getId);
        Page<AnswerEntity> entityPage = answerMapper.selectPage(Page.of(safePage, safeSize), query);
        Map<Long, String> nicknames = nicknames(entityPage.getRecords().stream()
                .map(AnswerEntity::getUserId).toList());
        Page<Answer> result = Page.of(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        result.setRecords(entityPage.getRecords().stream()
                .map(entity -> toAnswer(entity, nicknames.get(entity.getUserId())))
                .toList());
        return result;
    }

    @Transactional
    public Question answer(Long userId, Long questionId, QaRequests.CreateAnswer request) {
        requireOpenQuestion(questionId);
        LocalDateTime now = LocalDateTime.now();
        AnswerEntity answer = new AnswerEntity();
        answer.setQuestionId(questionId);
        answer.setUserId(userId);
        answer.setContent(request.content().trim());
        answer.setStatus(ANSWER_VISIBLE);
        answer.setLikeCount(0L);
        answer.setAccepted(0);
        answer.setCreatedAt(now);
        answer.setUpdatedAt(now);
        answer.setDeleted(0);
        answerMapper.insert(answer);

        int updated = questionMapper.update(null, new LambdaUpdateWrapper<QuestionEntity>()
                .eq(QuestionEntity::getId, questionId)
                .eq(QuestionEntity::getStatus, QUESTION_OPEN)
                .setSql("answer_count = answer_count + 1"));
        if (updated != 1) {
            throw new BusinessException("问题不存在或已关闭");
        }
        return detail(questionId);
    }

    private QuestionEntity requireOpenQuestion(Long questionId) {
        QuestionEntity entity = questionMapper.selectOne(new LambdaQueryWrapper<QuestionEntity>()
                .eq(QuestionEntity::getId, questionId)
                .eq(QuestionEntity::getStatus, QUESTION_OPEN));
        if (entity == null) throw new BusinessException("问题不存在或已关闭");
        return entity;
    }

    private String nicknameOf(Long userId) {
        User user = userMapper.selectById(userId);
        return user == null || user.getNickname() == null || user.getNickname().isBlank()
                ? "问课学员" : user.getNickname();
    }

    private Map<Long, String> nicknames(List<Long> userIds) {
        List<Long> distinctIds = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) return Collections.emptyMap();
        return userMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(User::getId,
                        user -> user.getNickname() == null || user.getNickname().isBlank()
                                ? "问课学员" : user.getNickname(),
                        (left, right) -> left));
    }

    private Question toQuestion(QuestionEntity entity, String nickname) {
        return new Question(entity.getId(), entity.getUserId(), nickname == null ? "问课学员" : nickname,
                entity.getCourseId(), entity.getTitle(), entity.getContent(), entity.getStatus(),
                Objects.requireNonNullElse(entity.getLikeCount(), 0L),
                Objects.requireNonNullElse(entity.getAnswerCount(), 0), entity.getCreatedAt());
    }

    private Answer toAnswer(AnswerEntity entity, String nickname) {
        return new Answer(entity.getId(), entity.getQuestionId(), entity.getUserId(),
                nickname == null ? "问课学员" : nickname, entity.getContent(),
                Objects.equals(entity.getAccepted(), 1),
                Objects.requireNonNullElse(entity.getLikeCount(), 0L), entity.getCreatedAt());
    }
}
