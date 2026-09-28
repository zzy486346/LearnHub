package com.learnhub.interaction.qa;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.auth.mapper.UserMapper;
import com.learnhub.auth.model.User;
import com.learnhub.common.exception.BusinessException;
import com.learnhub.course.mapper.CourseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QaServiceTest {
    @Mock private QuestionMapper questionMapper;
    @Mock private AnswerMapper answerMapper;
    @Mock private UserMapper userMapper;
    @Mock private CourseMapper courseMapper;

    private QaService service;

    @BeforeEach
    void setUp() {
        service = new QaService(questionMapper, answerMapper, userMapper, courseMapper);
    }

    @Test
    void createsCommunityQuestionAndPersistsIt() {
        when(userMapper.selectById(7L)).thenReturn(user(7L, "代码学习者"));
        when(questionMapper.insert(any(QuestionEntity.class))).thenAnswer(invocation -> {
            QuestionEntity entity = invocation.getArgument(0);
            entity.setId(101L);
            return 1;
        });

        Question question = service.create(7L,
                new QaRequests.CreateQuestion(null, " Redis ", " Lua 如何保证原子性？ "));

        ArgumentCaptor<QuestionEntity> captor = ArgumentCaptor.forClass(QuestionEntity.class);
        verify(questionMapper).insert(captor.capture());
        assertEquals("Redis", captor.getValue().getTitle());
        assertEquals("Lua 如何保证原子性？", captor.getValue().getContent());
        assertEquals("OPEN", captor.getValue().getStatus());
        assertEquals(101L, question.id());
        assertEquals("代码学习者", question.nickname());
    }

    @Test
    void rejectsQuestionWhenCourseDoesNotExist() {
        when(courseMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.create(7L,
                new QaRequests.CreateQuestion(99L, "问题", "内容")));

        verify(questionMapper, never()).insert(any(QuestionEntity.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsQuestionsWithRealAuthorNicknames() {
        QuestionEntity entity = question(10L, 7L, 3L, 2);
        when(questionMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<QuestionEntity> page = invocation.getArgument(0);
            page.setRecords(List.of(entity));
            page.setTotal(1);
            return page;
        });
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(user(7L, "云端漫步")));

        Page<Question> result = service.list(1, 10, 3L);

        assertEquals(1, result.getTotal());
        assertEquals("云端漫步", result.getRecords().get(0).nickname());
        assertEquals(2, result.getRecords().get(0).answerCount());
    }

    @Test
    void addsAnswerAndAtomicallyIncrementsAnswerCount() {
        QuestionEntity entity = question(10L, 7L, 3L, 0);
        when(questionMapper.selectOne(any())).thenReturn(entity);
        when(questionMapper.update(any(), any())).thenAnswer(invocation -> {
            entity.setAnswerCount(entity.getAnswerCount() + 1);
            return 1;
        });
        when(answerMapper.insert(any(AnswerEntity.class))).thenAnswer(invocation -> {
            AnswerEntity answer = invocation.getArgument(0);
            answer.setId(88L);
            return 1;
        });
        when(userMapper.selectById(7L)).thenReturn(user(7L, "提问者"));

        Question answered = service.answer(8L, 10L, new QaRequests.CreateAnswer(" 使用刷新令牌。 "));

        ArgumentCaptor<AnswerEntity> captor = ArgumentCaptor.forClass(AnswerEntity.class);
        verify(answerMapper).insert(captor.capture());
        assertEquals(10L, captor.getValue().getQuestionId());
        assertEquals(8L, captor.getValue().getUserId());
        assertEquals("使用刷新令牌。", captor.getValue().getContent());
        assertEquals("VISIBLE", captor.getValue().getStatus());
        assertEquals(1, answered.answerCount());
        verify(questionMapper).update(any(), any());
    }

    @Test
    void rejectsAnswerWhenQuestionDoesNotExist() {
        when(questionMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.answer(8L, 404L, new QaRequests.CreateAnswer("回答")));

        verify(answerMapper, never()).insert(any(AnswerEntity.class));
    }

    @Test
    void serializesSnowflakeIdsAsStringsForBrowsers() throws Exception {
        long snowflakeId = 2_104_518_190_187_606_018L;
        Question question = new Question(snowflakeId, 7L, "问课学员", null,
                "标题", "内容", "OPEN", 0, 0, null);

        String json = new ObjectMapper().writeValueAsString(question);

        assertTrue(json.contains("\"id\":\"2104518190187606018\""));
        assertTrue(json.contains("\"userId\":\"7\""));
    }

    private static QuestionEntity question(Long id, Long userId, Long courseId, int answerCount) {
        QuestionEntity entity = new QuestionEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setCourseId(courseId);
        entity.setTitle("JWT 如何续期？");
        entity.setContent("刷新令牌应该怎样使用？");
        entity.setStatus("OPEN");
        entity.setLikeCount(0L);
        entity.setAnswerCount(answerCount);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private static User user(Long id, String nickname) {
        User user = new User();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }
}
