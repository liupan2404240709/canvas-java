package com.infinitecanvas.canvas.config;

import com.infinitecanvas.canvas.infra.storage.StorageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 前后端分离开发期的跨域放行 + 上传文件的静态映射。
 *
 * <p>静态映射这块要注意：{@code ResourceHandler} 匹配的是 **servlet path**，
 * 所以这里写 {@code /media/**}，而浏览器实际访问 {@code /api/media/**}
 * （{@code /api} 是 context-path，不参与匹配）。
 * 和 Security 放行路径一样，最容易踩的坑就是把 context-path 也写进去。
 */
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    private final StorageProperties storageProperties;

    public WebMvcConfig(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
    }

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
     * 把磁盘目录映射成静态资源。
     *
     * <p>为什么需要它：{@code <img src>} / {@code <video src>} 不会带 Authorization 头，
     * 走不了 {@code /v1/files/{id}/content} 这种鉴权接口。文件本身是内容寻址的
     * （路径就是 sha256），不可枚举，所以这里放开只读访问是可接受的。
     * 生产环境更稳的做法是对象存储私有桶 + 预签名 URL，有效期几分钟，泄露了也很快失效。
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = storageProperties.rootPath().toUri().toString();
        if (!location.endsWith("/")) {
            // ResourceHandler 要求 location 以 / 结尾，否则会当成"父目录"处理
            location = location + "/";
        }
        registry.addResourceHandler(storageProperties.getPublicPrefix() + "/**")
                .addResourceLocations(location)
                // 内容寻址 = 同一路径的字节永不变，放心让浏览器缓存一天
                .setCachePeriod(86400);
    }
}
