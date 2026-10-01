package com.learnhub.interaction.like;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LikeRecordMapper extends BaseMapper<LikeRecordEntity> {
    @Insert("""
            <script>
            INSERT INTO like_record
                (id, user_id, target_type, target_id, active, event_sequence, created_at, updated_at)
            VALUES
            <foreach collection="records" item="item" separator=",">
                (#{item.id}, #{item.userId}, #{item.targetType}, #{item.targetId},
                 #{item.active}, #{item.eventSequence}, #{item.createdAt}, #{item.updatedAt})
            </foreach>
            ON DUPLICATE KEY UPDATE
                active = IF(VALUES(event_sequence) &gt; event_sequence, VALUES(active), active),
                updated_at = IF(VALUES(event_sequence) &gt; event_sequence, VALUES(updated_at), updated_at),
                event_sequence = GREATEST(event_sequence, VALUES(event_sequence))
            </script>
            """)
    int upsertBatch(@Param("records") List<LikeRecordEntity> records);

    @Select("SELECT COALESCE(MAX(event_sequence), 0) FROM like_record")
    long maxEventSequence();
}
