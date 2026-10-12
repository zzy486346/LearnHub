package com.learnhub.interaction.favorite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.learnhub.common.exception.BusinessException;
import com.learnhub.interaction.InteractionTargetService;
import com.learnhub.interaction.like.LikeTargetType;
import org.junit.jupiter.api.Test;

class FavoriteServiceTest {
    private final FavoriteRecordMapper mapper = mock(FavoriteRecordMapper.class);
    private final InteractionTargetService targets = mock(InteractionTargetService.class);
    private final FavoriteService service = new FavoriteService(mapper, targets);

    @Test
    void invisibleCourseAndUnsupportedTypeHaveNoDatabaseSideEffects() {
        doThrow(new BusinessException("不可见")).when(targets).requireVisible(7L, LikeTargetType.COURSE, 1L);
        assertThatThrownBy(() -> service.favorite(7L, LikeTargetType.COURSE, 1L))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.favorite(7L, LikeTargetType.ANSWER, 1L))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(mapper);
    }

    @Test
    void duplicateValidFavoriteRemainsIdempotent() {
        when(mapper.selectCount(any())).thenReturn(1L);
        assertThat(service.favorite(7L, LikeTargetType.COURSE, 1L)).isTrue();
        verify(targets).requireVisible(7L, LikeTargetType.COURSE, 1L);
        verify(mapper, never()).insert(any(FavoriteRecord.class));
    }

    @Test
    void staleFavoriteCanStillBeRemovedWithoutTargetVisibility() {
        assertThat(service.unfavorite(7L, LikeTargetType.COURSE, 1L)).isFalse();
        verify(mapper).delete(any());
        verifyNoInteractions(targets);
    }
}
