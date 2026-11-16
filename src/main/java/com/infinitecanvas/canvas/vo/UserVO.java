package com.infinitecanvas.canvas.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.infinitecanvas.canvas.entity.User;

import java.time.LocalDateTime;

/**
 * 用户出参。刻意不包含 passwordHash / deleted / status 这些内部字段。
 * id 用 ToStringSerializer 输出成字符串：19 位雪花 ID 超过 JS Number 的安全整数范围，
 * 前端 JSON.parse 后会丢精度。只在这一个字段上标注，而不是全局配置。
 */
public record UserVO(

        @JsonSerialize(using = ToStringSerializer.class)
        Long id,

        String username,
        String nickname,
        String email,
        String avatarUrl,
        LocalDateTime createdAt
) {

    public static UserVO from(User user) {
        if (user == null) {
            return null;
        }
        return new UserVO(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getEmail(),
                user.getAvatarUrl(),
                user.getCreatedAt()
        );
    }
}
