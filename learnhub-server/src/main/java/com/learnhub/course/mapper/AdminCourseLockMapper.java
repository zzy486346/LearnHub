package com.learnhub.course.mapper;

import com.learnhub.course.model.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminCourseLockMapper {
    @Select("SELECT * FROM course WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Course selectForUpdate(@Param("id") Long id);
}
