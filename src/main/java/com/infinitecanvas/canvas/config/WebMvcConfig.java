package com.infinitecanvas.canvas.config;

import com.infinitecanvas.canvas.infra.storage.StorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域放行 + 控制台入口重定向。
 *
 * <p>这里曾经还有一个 {@code addResourceHandlers}，把上传目录直接映射成静态资源
 * （{@code /media/**} 读磁盘，零开销）。阶段 3 实测后改掉了，原因是 Content-Type：
 * 文件名是 sha256、没有扩展名，ResourceHandler 推断不出类型，一律返回
 * {@code application/octet-stream}；配上 Spring Security 默认的
 * {@code X-Content-Type-Options: nosniff}，浏览器会拒绝把它当图片渲染，页面上一片破图。
 *
 * <p>现在改由 {@code MediaController} 提供 {@code /media/**}：从 {@code t_file.mime_type}
 * 拿真实类型，顺带白得 Range 支持（{@code <video>} 拖进度条要用）。
 *
 * <p>仍然保留 {@code @EnableConfigurationProperties}：{@link StorageProperties}
 * 本身没有 {@code @Component}，是这里把它注册成 Bean 的。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id", "Content-Range", "Accept-Ranges")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 让 /api/demo 和 /api/demo/ 都能进控制台，不用记 index.html。
     *
     * <p>redirect 的目标写 servlet path（不带 context-path），浏览器会基于当前地址自动补上 /api。
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/demo", "/demo/index.html");
        registry.addRedirectViewController("/demo/", "/demo/index.html");
    }
}
