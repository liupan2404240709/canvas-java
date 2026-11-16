package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.dto.UpdateProfileRequest;
import com.infinitecanvas.canvas.vo.UserVO;

public interface UserService {

    UserVO me(Long userId);

    UserVO updateProfile(Long userId, UpdateProfileRequest request);
}
