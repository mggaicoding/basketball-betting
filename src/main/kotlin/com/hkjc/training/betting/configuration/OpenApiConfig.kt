package com.hkjc.training.betting.configuration

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BEARER_SCHEME = "bearer-jwt"

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    /** Declaring the scheme is what puts the Authorize button in Swagger UI. */
    @Bean
    fun bettingOpenApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Basketball Betting API")
                    .version("v1")
                    .description(
                        "Training API. Generate a token with `./gradlew generateDemoTokens`, " +
                            "then paste it into Authorize — bets need bets:write, games need games:read.",
                    ),
            ).components(
                Components().addSecuritySchemes(
                    BEARER_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT"),
                ),
            ).addSecurityItem(SecurityRequirement().addList(BEARER_SCHEME))
}
