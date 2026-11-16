package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.dto.UpdateProfileRequest;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.UserService;
import com.infinitecanvas.canvas.vo.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户相关接口。
 * 路径里刻意不出现 userId：用户 id 一律从 token 里取，
 * 只要 URL 里能传别人的 id，就必须在每个方法里补一次归属校验，漏一个就是越权漏洞。
 */
@Tag(name = "用户", description = "当前登录用户的资料")
@RestController
@RequestMapping(ApiPath.USERS)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "当前用户信息", description = "前端刷新页面后用它恢复登录态")
    @GetMapping("/me")
    public Result<UserVO> me() {
        return Result.ok(userService.me(SecurityUtils.currentUserId()));
    }

    @Operation(summary = "修改个人资料", description = "只传需要修改的字段，null 表示不改")
    @PutMapping("/me")
    public Result<UserVO> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return Result.ok(userService.updateProfile(SecurityUtils.currentUserId(), request));
    }
}
