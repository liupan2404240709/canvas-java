package com.infinitecanvas.canvas;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/**
 * @MapperScan 在这里统一声明，mapper 接口就不用一个个写 @Mapper。
 * 排除 UserDetailsServiceAutoConfiguration：我们没有走 Spring Security 的表单登录，
 * 也不提供 UserDetailsService（登录是自己在 Service 里用 BCrypt 校验的），
 * 不排除的话启动时会打印一条 "Using generated security password: xxxx" 的干扰信息。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.infinitecanvas.canvas.mapper")
public class CanvasApplication {

    public static void main(String[] args) {
        SpringApplication.run(CanvasApplication.class, args);
    }
}
