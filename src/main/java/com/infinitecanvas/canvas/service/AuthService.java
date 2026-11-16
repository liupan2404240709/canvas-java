package com.infinitecanvas.canvas.service;

import com.infinitecanvas.canvas.dto.LoginRequest;
import com.infinitecanvas.canvas.dto.RegisterRequest;
import com.infinitecanvas.canvas.security.LoginUser;
import com.infinitecanvas.canvas.vo.TokenVO;

/**
 * 认证业务。接口与实现分离是为了让 Controller 只依赖契约，
 * 也方便阶段 2 之后给这些方法加事务 / 缓存 / 埋点时不用动调用方。
 */
public interface AuthService {

    TokenVO register(RegisterRequest request);

    TokenVO login(LoginRequest request, String clientIp);

    TokenVO refresh(String refreshToken);

    void logout(LoginUser current, String refreshToken);
}
