package com.learnhub.interaction.like;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LikeEventInboxMapper extends BaseMapper<LikeEventInboxEntity> {
    @Insert("""
            <script>
            INSERT IGNORE INTO like_event_inbox
              (id, event_id, user_id, target_type, target_id, liked, event_sequence, event_version,
               status, occurred_at, created_at)
            VALUES
            <foreach collection="events" item="item" separator=",">
              (#{item.id}, #{item.eventId}, #{item.userId}, #{item.targetType}, #{item.targetId},
               #{item.liked}, #{item.eventSequence}, #{item.eventVersion}, #{item.status},
               #{item.occurredAt}, #{item.createdAt})
            </foreach>
            </script>
            """)
    int insertIgnoreBatch(@Param("events") List<LikeEventInboxEntity> events);

    @Select("""
            <script>
            SELECT * FROM like_event_inbox
            WHERE status = 'PENDING' AND event_id IN
            <foreach collection="eventIds" item="eventId" open="(" separator="," close=")">
              #{eventId}
            </foreach>
            FOR UPDATE
            </script>
            """)
    List<LikeEventInboxEntity> selectPendingForUpdate(@Param("eventIds") List<String> eventIds);

    @Select("SELECT COALESCE(MAX(event_sequence), 0) FROM like_event_inbox")
    long maxEventSequence();

    @Update("""
            <script>
            UPDATE like_event_inbox SET status = 'PROCESSED', processed_at = #{processedAt}
            WHERE status = 'PENDING' AND event_id IN
            <foreach collection="eventIds" item="eventId" open="(" separator="," close=")">
              #{eventId}
            </foreach>
            </script>
            """)
    int markProcessed(@Param("eventIds") List<String> eventIds,
                      @Param("processedAt") LocalDateTime processedAt);
}
