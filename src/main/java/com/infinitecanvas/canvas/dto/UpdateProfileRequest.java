package com.infinitecanvas.canvas.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新个人资料。字段都可空，null 表示"这次不改这一项"，
 * 所以不需要区分空字符串和 null 的语义。
 */
public record UpdateProfileRequest(

        @Size(max = 32, message = "昵称最长 32 个字符")
        String nickname,

        @Size(max = 512, message = "头像地址过长")
        @Pattern(regexp = "^$|^(https?://|/).*", message = "头像需为 http(s) 地址或站内路径")
        String avatarUrl
) {
}
