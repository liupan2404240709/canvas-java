package com.infinitecanvas.canvas.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.infinitecanvas.canvas.common.api.ResultCode;
import com.infinitecanvas.canvas.common.exception.BizException;
import com.infinitecanvas.canvas.dto.UpdateProfileRequest;
import com.infinitecanvas.canvas.entity.User;
import com.infinitecanvas.canvas.mapper.UserMapper;
import com.infinitecanvas.canvas.service.UserService;
import com.infinitecanvas.canvas.vo.UserVO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;

    public UserServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserVO me(Long userId) {
        return UserVO.from(requireUser(userId));
    }

    @Override
    public UserVO updateProfile(Long userId, UpdateProfileRequest request) {
        requireUser(userId);

        // 用 UpdateWrapper 只更新传了值的字段：null 表示"这次不改"，
        // 不能用 updateById(entity)，那样会把没传的字段覆盖成 null
        LambdaUpdateWrapper<User> wrapper = new LambdaUpdateWrapper<User>().eq(User::getId, userId);
        boolean changed = false;
        if (request.nickname() != null) {
            wrapper.set(User::getNickname, request.nickname().trim());
            changed = true;
        }
        if (request.avatarUrl() != null) {
            wrapper.set(User::getAvatarUrl, StringUtils.hasText(request.avatarUrl()) ? request.avatarUrl().trim() : null);
            changed = true;
        }
        if (!changed) {
            return me(userId);
        }
        userMapper.update(null, wrapper);
        return me(userId);
    }

    private User requireUser(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // token 有效但用户被删了：按凭证失效处理，前端会直接跳登录页
            throw new BizException(ResultCode.TOKEN_REVOKED);
        }
        return user;
    }
}
