package com.learnhub.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnhub.course.model.CourseTag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface CourseTagMapper extends BaseMapper<CourseTag> {
    @Select("SELECT * FROM tag WHERE id = #{id} FOR UPDATE")
    CourseTag selectForUpdate(@Param("id") Long id);

    @Select("""
            SELECT DISTINCT t.*
            FROM tag t
            JOIN course_tag ct ON ct.tag_id = t.id
            JOIN course c ON c.id = ct.course_id
            WHERE c.status = 'PUBLISHED' AND c.deleted = 0
            ORDER BY t.name, t.id
            """)
    List<CourseTag> selectPublishedTags();
}
