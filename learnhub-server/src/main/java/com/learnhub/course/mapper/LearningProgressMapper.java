package com.learnhub.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnhub.course.model.LearningProgress;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LearningProgressMapper extends BaseMapper<LearningProgress> {
    @Select("""
            SELECT lp.* FROM learning_progress lp
            JOIN course_lesson cl ON cl.id = lp.lesson_id
            JOIN course_chapter cc ON cc.id = cl.chapter_id
            WHERE lp.user_id = #{userId} AND cc.course_id = #{courseId}
            ORDER BY cc.sort_order, cl.sort_order
            """)
    List<LearningProgress> selectByUserAndCourse(@Param("userId") Long userId, @Param("courseId") Long courseId);

    @Select("""
            SELECT COUNT(*) FROM course_lesson cl
            JOIN course_chapter cc ON cc.id = cl.chapter_id
            WHERE cl.id = #{lessonId} AND cc.course_id = #{courseId}
            """)
    long countLessonInCourse(@Param("lessonId") Long lessonId, @Param("courseId") Long courseId);
}
