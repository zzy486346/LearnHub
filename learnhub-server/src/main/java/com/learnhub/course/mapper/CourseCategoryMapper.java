package com.learnhub.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnhub.course.model.CourseCategory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseCategoryMapper extends BaseMapper<CourseCategory> {
    @Select("SELECT * FROM course_category WHERE id = #{id} FOR UPDATE")
    CourseCategory selectForUpdate(@Param("id") Long id);
}
