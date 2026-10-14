package com.learnhub.order;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CourseOrderMapper extends BaseMapper<CourseOrder> {
    @Select("""
            SELECT * FROM course_order
            WHERE user_id = #{userId} AND course_id = #{courseId}
            """)
    CourseOrder selectUserCourse(@Param("userId") Long userId, @Param("courseId") Long courseId);

    @Select("""
            SELECT * FROM course_order
            WHERE id = #{orderId} AND user_id = #{userId}
            FOR UPDATE
            """)
    CourseOrder selectOwnedForUpdate(@Param("orderId") Long orderId, @Param("userId") Long userId);

    @Select("""
            SELECT * FROM course_order
            WHERE status = 'PENDING' AND expires_at <= #{now}
            ORDER BY expires_at ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<CourseOrder> selectExpiredForUpdate(@Param("now") LocalDateTime now, @Param("limit") int limit);
}
