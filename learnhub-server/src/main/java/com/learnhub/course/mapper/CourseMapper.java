package com.learnhub.course.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnhub.course.model.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {
    @Select("SELECT * FROM course WHERE id = #{id} AND status = 'PUBLISHED' FOR UPDATE")
    Course selectPublishedForPurchase(@Param("id") Long id);
}
