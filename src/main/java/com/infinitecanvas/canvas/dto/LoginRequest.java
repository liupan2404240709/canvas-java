package com.infinitecanvas.canvas.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录入参。account 同时接受用户名和邮箱，减少用户"到底该填哪个"的困惑；
 * 代价是查询要多一次 or 条件，需要在设计文档里说清索引怎么走。
 */
public record LoginRequest(

        @NotBlank(message = "账号不能为空")
        @Size(max = 128, message = "账号过长")
        String account,

        @NotBlank(message = "密码不能为空")
        String password
) {
}
