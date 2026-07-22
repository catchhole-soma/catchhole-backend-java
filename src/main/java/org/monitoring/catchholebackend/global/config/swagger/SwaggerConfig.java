package org.monitoring.catchholebackend.global.config.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.monitoring.catchholebackend.global.config.security.SecurityConstant;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    private static final String BEARER_SECURITY_SCHEME_NAME = "bearerAuth";
    private static final String INTERNAL_API_KEY_SECURITY_SCHEME_NAME = "internalApiKey";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("CatchHole API")
                        .version("v1")
                        .description("CatchHole Backend API 문서"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(BEARER_SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT"))
                        .addSecuritySchemes(INTERNAL_API_KEY_SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SecurityConstant.INTERNAL_API_KEY_HEADER)
                                        .type(SecurityScheme.Type.APIKEY)
                                        .in(SecurityScheme.In.HEADER)));
    }
}
