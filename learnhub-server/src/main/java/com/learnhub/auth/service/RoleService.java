package com.learnhub.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.learnhub.auth.mapper.UserRoleMapper;
import com.learnhub.auth.model.UserRole;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RoleService {
    private final UserRoleMapper mapper;

    public RoleService(UserRoleMapper mapper) {
        this.mapper = mapper;
    }

    public List<String> rolesForUser(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getUserId, userId)
                        .orderByAsc(UserRole::getRoleCode)).stream()
                .map(UserRole::getRoleCode)
                .distinct()
                .toList();
    }
}
