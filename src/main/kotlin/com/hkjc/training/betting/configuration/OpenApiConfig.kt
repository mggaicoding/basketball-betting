package com.hkjc.training.betting.configuration

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    @Bean
    fun bettingOpenApi(): OpenAPI =
        OpenAPI().info(
            Info()
                .title("Basketball Betting API")
                .version("v1")
                .description("Training API for the full-day backend course."),
        )
}
