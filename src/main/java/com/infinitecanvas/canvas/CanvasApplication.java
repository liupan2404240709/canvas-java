package com.infinitecanvas.canvas;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @MapperScan 在这里统一声明，mapper 接口就不用一个个写 @Mapper。
 * 排除 UserDetailsServiceAutoConfiguration：我们没有走 Spring Security 的表单登录，
 * 也不提供 UserDetailsService（登录是自己在 Service 里用 BCrypt 校验的），
 * 不排除的话启动时会打印一条 "Using generated security password: xxxx" 的干扰信息。
 *
 * <p>@EnableScheduling 是阶段 4 加的：提示词源需要定时同步。
 * 加在启动类而不是某个 @Configuration 上，是因为它属于"应用级开关"，
 * 让人一眼能看出这个服务有后台任务。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@MapperScan("com.infinitecanvas.canvas.mapper")
@EnableScheduling
public class CanvasApplication {

    public static void main(String[] args) {
        SpringApplication.run(CanvasApplication.class, args);
    }
}
