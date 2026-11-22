package com.enterprise.investment;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfiguration {
    @Bean
    OpenAPI investmentOpenAPI() {
        return new OpenAPI().info(new Info().title("招商平台业务 API").version("1.0")
                .description("业务接口需登录 Bearer Token 并执行 RBAC；/api/collector/* 使用采集服务令牌。接口文档默认关闭。"))
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer"))
                        .addSecuritySchemes("collectorToken", new SecurityScheme().type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER).name("X-Collector-Token")));
    }
}
