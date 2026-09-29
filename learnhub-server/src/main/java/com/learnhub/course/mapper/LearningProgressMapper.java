package com.learnhub.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnhub.course.model.LearningProgress;
import com.learnhub.profile.model.ProfileRecentLearningRow;
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

    @Select("""
            SELECT COUNT(*) FROM (
                SELECT cc.course_id
                FROM learning_progress lp
                JOIN course_lesson cl ON cl.id = lp.lesson_id
                JOIN course_chapter cc ON cc.id = cl.chapter_id
                WHERE lp.user_id = #{userId}
                GROUP BY cc.course_id
                HAVING SUM(CASE WHEN lp.completed = 1 THEN 1 ELSE 0 END) < (
                    SELECT COUNT(*)
                    FROM course_lesson all_lessons
                    JOIN course_chapter all_chapters ON all_chapters.id = all_lessons.chapter_id
                    WHERE all_chapters.course_id = cc.course_id
                )
            ) active_courses
            """)
    long countLearningCourses(@Param("userId") Long userId);

    @Select("""
            SELECT cc.course_id AS course_id,
                   c.title AS course_title,
                   cl.id AS lesson_id,
                   cl.title AS lesson_title,
                   lp.position_seconds AS position_seconds,
                   cl.duration_seconds AS duration_seconds,
                   lp.completed AS completed,
                   lp.last_learned_at AS last_learned_at
            FROM learning_progress lp
            JOIN course_lesson cl ON cl.id = lp.lesson_id
            JOIN course_chapter cc ON cc.id = cl.chapter_id
            JOIN course c ON c.id = cc.course_id
            WHERE lp.user_id = #{userId}
            ORDER BY lp.last_learned_at DESC
            LIMIT 1
            """)
    ProfileRecentLearningRow selectRecentLearning(@Param("userId") Long userId);
}
