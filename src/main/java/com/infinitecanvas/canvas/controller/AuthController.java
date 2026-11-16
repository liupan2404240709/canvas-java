package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.dto.LoginRequest;
import com.infinitecanvas.canvas.dto.RefreshTokenRequest;
import com.infinitecanvas.canvas.dto.RegisterRequest;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.AuthService;
import com.infinitecanvas.canvas.vo.TokenVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。Controller 只做三件事：接参、调 Service、包响应，
 * 不写任何业务判断——这样同一段逻辑被定时任务或消息消费者复用时不用复制。
 */
@Tag(name = "认证", description = "注册、登录、刷新令牌、登出")
@RestController
@RequestMapping(ApiPath.AUTH)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "注册", description = "注册成功直接返回令牌，前端不需要再调一次登录")
    @SecurityRequirements
    @PostMapping("/register")
    public Result<TokenVO> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    @Operation(summary = "登录", description = "account 支持用户名或邮箱")
    @SecurityRequirements
    @PostMapping("/login")
    public Result<TokenVO> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return Result.ok(authService.login(request, servletRequest.getRemoteAddr()));
    }

    @Operation(summary = "刷新令牌", description = "旧 refreshToken 会被立即作废（轮换）")
    @SecurityRequirements
    @PostMapping("/refresh")
    public Result<TokenVO> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return Result.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "登出", description = "拉黑当前 accessToken；请求体可带 refreshToken 一并撤销")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) RefreshTokenRequest request) {
        authService.logout(SecurityUtils.currentUser(), request == null ? null : request.refreshToken());
        return Result.ok();
    }
}
