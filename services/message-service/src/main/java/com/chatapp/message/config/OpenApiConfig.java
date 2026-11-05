package com.chatapp.message.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI messageServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("chat-app Message Service")
                        .description("Messages, history, receipts and sync. Real-time events use STOMP over /ws (see WEBSOCKET_DESIGN.md).")
                        .version("0.3.0"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
    }
}
