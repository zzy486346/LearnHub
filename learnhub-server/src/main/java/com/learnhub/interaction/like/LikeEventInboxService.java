package com.learnhub.interaction.like;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LikeEventInboxService {
    private final LikeEventInboxMapper mapper;

    public LikeEventInboxService(LikeEventInboxMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public List<LikeEvent> persistAndGetPending(List<LikeEvent> events) {
        if (events.isEmpty()) return List.of();
        LocalDateTime now = LocalDateTime.now();
        List<LikeEventInboxEntity> rows = events.stream().map(event -> toEntity(event, now)).toList();
        mapper.insertIgnoreBatch(rows);
        Set<String> pendingIds = mapper.selectList(new LambdaQueryWrapper<LikeEventInboxEntity>()
                        .select(LikeEventInboxEntity::getEventId)
                        .eq(LikeEventInboxEntity::getStatus, "PENDING")
                        .in(LikeEventInboxEntity::getEventId,
                                events.stream().map(LikeEvent::eventId).distinct().toList()))
                .stream().map(LikeEventInboxEntity::getEventId).collect(Collectors.toSet());
        return events.stream().filter(event -> pendingIds.contains(event.eventId())).toList();
    }

    public List<LikeEvent> pending(int limit) {
        return mapper.selectList(new LambdaQueryWrapper<LikeEventInboxEntity>()
                        .eq(LikeEventInboxEntity::getStatus, "PENDING")
                        .orderByAsc(LikeEventInboxEntity::getId)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 1000))))
                .stream().map(this::toEvent).toList();
    }

    void markProcessed(List<String> eventIds, LocalDateTime processedAt) {
        if (!eventIds.isEmpty()) mapper.markProcessed(eventIds, processedAt);
    }

    List<LikeEventInboxEntity> lockPending(List<String> eventIds) {
        if (eventIds.isEmpty()) return List.of();
        return mapper.selectPendingForUpdate(eventIds);
    }

    long maxSequence() { return mapper.maxEventSequence(); }

    private LikeEventInboxEntity toEntity(LikeEvent event, LocalDateTime now) {
        LikeEventInboxEntity row = new LikeEventInboxEntity();
        row.setId(IdWorker.getId());
        row.setEventId(event.eventId());
        row.setUserId(event.userId());
        row.setTargetType(event.targetType().name());
        row.setTargetId(event.targetId());
        row.setLiked(event.liked());
        row.setEventSequence(event.sequence());
        row.setEventVersion(event.version());
        row.setStatus("PENDING");
        row.setOccurredAt(LocalDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC));
        row.setCreatedAt(now);
        return row;
    }

    private LikeEvent toEvent(LikeEventInboxEntity row) {
        return new LikeEvent(row.getEventId(), row.getUserId(), LikeTargetType.valueOf(row.getTargetType()),
                row.getTargetId(), Boolean.TRUE.equals(row.getLiked()), row.getEventSequence(),
                row.getOccurredAt().toInstant(ZoneOffset.UTC), row.getEventVersion());
    }
}
