package com.learnhub.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.learnhub.auth.model.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {}
