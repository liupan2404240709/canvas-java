package com.infinitecanvas.canvas.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI canvasOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("无限画布后端 API")
                        .version("0.0.1")
                        .description("画布项目、素材库、提示词库、生成任务与对话助手的后端接口"))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("只粘贴 accessToken 本身，不要带 Bearer 前缀，Swagger 会自动拼")))
                // 声明还不够，必须再挂一个全局 SecurityRequirement，
                // 生成的 OpenAPI 文档里才会出现 security 字段，Swagger UI 才知道要把 token 塞进请求头。
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }
}
