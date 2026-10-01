package com.learnhub.interaction.like;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LikeCountMapper {
    @Update("UPDATE course SET like_count = #{count}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{targetId}")
    int setCourseCount(@Param("targetId") Long targetId, @Param("count") long count);

    @Update("UPDATE question SET like_count = #{count}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{targetId}")
    int setQuestionCount(@Param("targetId") Long targetId, @Param("count") long count);

    @Update("UPDATE answer SET like_count = #{count}, updated_at = CURRENT_TIMESTAMP(3) WHERE id = #{targetId}")
    int setAnswerCount(@Param("targetId") Long targetId, @Param("count") long count);

    @Update("""
            <script>
            UPDATE course
            SET like_count = GREATEST(0, like_count + CASE id
                <foreach collection="items" item="item">
                    WHEN #{item.targetId} THEN #{item.delta}
                </foreach>
                ELSE 0 END),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id IN
                <foreach collection="items" item="item" open="(" separator="," close=")">#{item.targetId}</foreach>
            </script>
            """)
    int updateCourseCounts(@Param("items") List<LikeCountDelta> items);

    @Update("""
            <script>
            UPDATE question
            SET like_count = GREATEST(0, like_count + CASE id
                <foreach collection="items" item="item">
                    WHEN #{item.targetId} THEN #{item.delta}
                </foreach>
                ELSE 0 END),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id IN
                <foreach collection="items" item="item" open="(" separator="," close=")">#{item.targetId}</foreach>
            </script>
            """)
    int updateQuestionCounts(@Param("items") List<LikeCountDelta> items);

    @Update("""
            <script>
            UPDATE answer
            SET like_count = GREATEST(0, like_count + CASE id
                <foreach collection="items" item="item">
                    WHEN #{item.targetId} THEN #{item.delta}
                </foreach>
                ELSE 0 END),
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE id IN
                <foreach collection="items" item="item" open="(" separator="," close=")">#{item.targetId}</foreach>
            </script>
            """)
    int updateAnswerCounts(@Param("items") List<LikeCountDelta> items);
}
