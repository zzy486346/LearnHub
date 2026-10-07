package com.learnhub.search;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CourseIndexTaskMapper extends BaseMapper<CourseIndexTaskEntity> {
    @Insert("""
            INSERT INTO search_index_task
                (course_id, generation, attempts, next_retry_at, last_error, created_at, updated_at)
            VALUES (#{courseId}, 1, 0, CURRENT_TIMESTAMP(3), NULL, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
            ON DUPLICATE KEY UPDATE
                generation = generation + 1,
                attempts = 0,
                next_retry_at = CURRENT_TIMESTAMP(3),
                last_error = NULL,
                updated_at = CURRENT_TIMESTAMP(3)
            """)
    int enqueue(@Param("courseId") Long courseId);

    @Select("""
            SELECT id, course_id, generation, attempts, next_retry_at, last_error, created_at, updated_at
            FROM search_index_task
            WHERE next_retry_at <= CURRENT_TIMESTAMP(3)
            ORDER BY next_retry_at, id
            LIMIT #{limit}
            """)
    List<CourseIndexTaskEntity> selectDue(@Param("limit") int limit);

    @Delete("DELETE FROM search_index_task WHERE course_id = #{courseId} AND generation = #{generation}")
    int deleteIfGeneration(@Param("courseId") Long courseId, @Param("generation") long generation);

    @Update("""
            UPDATE search_index_task
            SET attempts = attempts + 1,
                next_retry_at = #{nextRetryAt},
                last_error = #{lastError},
                updated_at = CURRENT_TIMESTAMP(3)
            WHERE course_id = #{courseId} AND generation = #{generation}
            """)
    int failIfGeneration(@Param("courseId") Long courseId,
                         @Param("generation") long generation,
                         @Param("nextRetryAt") LocalDateTime nextRetryAt,
                         @Param("lastError") String lastError);
}
