package com.learnhub.interaction.favorite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnhub.interaction.like.LikeTargetType;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FavoriteService {
    private final FavoriteRecordMapper mapper;

    public FavoriteService(FavoriteRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public boolean favorite(Long userId, LikeTargetType type, Long targetId) {
        if (exists(userId, type, targetId)) return true;
        FavoriteRecord record = new FavoriteRecord();
        record.setUserId(userId);
        record.setTargetType(type.name());
        record.setTargetId(targetId);
        record.setCreatedAt(LocalDateTime.now());
        try {
            mapper.insert(record);
        } catch (DuplicateKeyException ignored) {
            // The unique index makes concurrent duplicate requests idempotent.
        }
        return true;
    }

    @Transactional
    public boolean unfavorite(Long userId, LikeTargetType type, Long targetId) {
        mapper.delete(query(userId, type, targetId));
        return false;
    }

    public boolean exists(Long userId, LikeTargetType type, Long targetId) {
        return mapper.selectCount(query(userId, type, targetId)) > 0;
    }

    private LambdaQueryWrapper<FavoriteRecord> query(Long userId, LikeTargetType type, Long targetId) {
        return new LambdaQueryWrapper<FavoriteRecord>()
                .eq(FavoriteRecord::getUserId, userId)
                .eq(FavoriteRecord::getTargetType, type.name())
                .eq(FavoriteRecord::getTargetId, targetId);
    }
}
