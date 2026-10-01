package com.learnhub.interaction.like;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learnhub.course.mapper.CourseMapper;
import java.util.List;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class LikeServiceTest {
    private StringRedisTemplate redis;
    private RabbitTemplate rabbit;
    private LikeEventAggregationService aggregationService;
    private SetOperations<String, String> sets;
    private LikeSequenceService sequences;
    private LikeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        rabbit = mock(RabbitTemplate.class);
        aggregationService = mock(LikeEventAggregationService.class);
        sets = mock(SetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        sequences = mock(LikeSequenceService.class);
        when(sequences.next()).thenReturn(1L);
        service = new LikeService(provider(redis), provider(rabbit), aggregationService,
                new ObjectMapper().findAndRegisterModules(), false, Duration.ofSeconds(1), sequences);
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestChainDoesNotWriteCourseDatabase() {
        CourseMapper courseMapper = mock(CourseMapper.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        when(sets.size(LikeRedisKeys.userSet(LikeTargetType.COURSE, 1002L))).thenReturn(1L);

        LikeResult result = service.setLike(7L, LikeTargetType.COURSE, 1002L, true);

        assertThat(result).isEqualTo(new LikeResult(true, 1L));
        verifyNoInteractions(courseMapper);
        verify(aggregationService).aggregate(anyList());
        verify(rabbit, never()).convertAndSend(eq(LikeService.EXCHANGE), eq(LikeService.ROUTING_KEY),
                any(Object.class), any(CorrelationData.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void atomicStateChangeStoresEventInPendingPublishBeforeDispatch() throws Exception {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        when(sets.size(any(String.class))).thenReturn(1L);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        service.setLike(7L, LikeTargetType.COURSE, 1002L, true);

        verify(redis).execute(any(RedisScript.class), keys.capture(), args.capture());
        assertThat(keys.getValue()).containsExactly(
                LikeRedisKeys.userSet(LikeTargetType.COURSE, 1002L),
                LikeRedisKeys.PENDING_PUBLISH,
                LikeRedisKeys.TARGETS,
                LikeRedisKeys.RECONCILE_LOCK);
        assertThat(args.getValue()).hasSize(5);
        assertThat(args.getValue()[0]).isEqualTo("7");
        assertThat(args.getValue()[1]).isEqualTo("1");
        LikeEvent stored = new ObjectMapper().findAndRegisterModules()
                .readValue(args.getValue()[3].toString(), LikeEvent.class);
        assertThat(stored.eventId()).isEqualTo(args.getValue()[2]);
        assertThat(stored.userId()).isEqualTo(7L);
        assertThat(stored.targetType()).isEqualTo(LikeTargetType.COURSE);
        assertThat(stored.targetId()).isEqualTo(1002L);
        assertThat(stored.liked()).isTrue();
        assertThat(args.getValue()[4]).isEqualTo("COURSE:1002");
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateDesiredStateDoesNotPublishAnotherEvent() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        when(sets.size(any(String.class))).thenReturn(1L);

        service.setLike(7L, LikeTargetType.COURSE, 1002L, true);

        verifyNoInteractions(aggregationService);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
