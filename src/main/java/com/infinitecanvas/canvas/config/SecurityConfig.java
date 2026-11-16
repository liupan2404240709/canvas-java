package com.infinitecanvas.canvas.config;

import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.security.JwtAuthenticationFilter;
import com.infinitecanvas.canvas.security.JwtProperties;
import com.infinitecanvas.canvas.security.LoginProtectionProperties;
import com.infinitecanvas.canvas.security.RestAccessDeniedHandler;
import com.infinitecanvas.canvas.security.RestAuthenticationEntryPoint;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 安全配置。几个关键取舍：
 *
 * 1. sessionCreationPolicy = STATELESS：服务端不存会话，每次请求靠 JWT 自证身份，
 *    这样多实例部署不用做 session 共享；
 * 2. 关掉 csrf：CSRF 攻击利用的是浏览器自动带 Cookie，而我们用 Authorization 头，
 *    不存在自动携带，留着反而会让 POST 请求都要带 csrf token；
 * 3. 关掉 formLogin / httpBasic / logout：这些默认端点会污染接口面，
 *    登录登出我们自己写在 /v1/auth 下；
 * 4. 放行路径里**不要写 context-path**：Security 的匹配针对 servlet path（/v1/xxx），
 *    /api 是 context-path，不参与匹配。这是接 Security 时最常见的 401 来源。
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({JwtProperties.class, LoginProtectionProperties.class})
public class SecurityConfig {

    /** 完全公开的路径。新增公开接口时必须同步加到这里，否则会被 401 拦掉。 */
    private static final String[] PUBLIC_PATHS = {
            ApiPath.AUTH + "/register",
            ApiPath.AUTH + "/login",
            ApiPath.AUTH + "/refresh",
            ApiPath.V1 + "/health",
            // 上传文件的只读静态映射：<img>/<video> 不会带 Authorization 头。
            // 路径是内容寻址的 sha256，不可枚举；路径同样不写 context-path。
            ApiPath.MEDIA + "/**",
            // 接口文档相关
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            // Spring Boot 自己的错误转发路径，不放开会把 404 变成 401
            "/error"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtAuthenticationFilter jwtAuthenticationFilter,
                                                   RestAuthenticationEntryPoint authenticationEntryPoint,
                                                   RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // 复用 WebMvcConfig 里配置的 CORS 规则；不开启的话预检请求会先被 Security 拦成 401
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        // 预检请求不带 Authorization，必须放行
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // 放在用户名密码过滤器之前：我们的认证不依赖表单登录
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * BCrypt：自带随机盐，同一个密码每次加密结果都不同，数据库泄露也难反查。
     * 强度用默认 10（2^10 次迭代），单次校验约几十毫秒，是安全性与登录耗时之间的常规平衡点。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
