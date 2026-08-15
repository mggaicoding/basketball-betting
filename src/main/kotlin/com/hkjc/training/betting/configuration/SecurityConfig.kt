package com.hkjc.training.betting.configuration

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer
import org.springframework.security.config.annotation.web.configurers.oauth2.server.resource.OAuth2ResourceServerConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.beans.factory.annotation.Value
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

const val DEMO_SECRET = "hkjc-training-local-secret-key-32-bytes"

private typealias AuthorizeHttpRequestsRegistry =
    AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry

@Configuration(proxyBeanMethods = false)
class SecurityConfig(
    @Value("\${training.cors.allowed-origins:http://localhost:8081}")
    private val allowedOrigins: String,
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .cors(Customizer.withDefaults())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.applyScopeRules() }
            .oauth2ResourceServer { it.applyJwtWithJsonErrors() }
            .build()

    @Bean
    fun jwtDecoder(): JwtDecoder =
        NimbusJwtDecoder
            .withSecretKey(demoSecretKey())
            .macAlgorithm(MacAlgorithm.HS256)
            .build()

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()
        configuration.allowedOrigins = allowedOrigins.split(',').map { it.trim() }
        configuration.allowedMethods = listOf("GET", "POST", "OPTIONS")
        configuration.allowedHeaders = listOf("Authorization", "Content-Type", "X-Trace-Id")
        configuration.exposedHeaders = listOf("X-Trace-Id")
        configuration.maxAge = 3600
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/**", configuration)
        }
    }
}

fun demoSecretKey(): SecretKey = SecretKeySpec(DEMO_SECRET.toByteArray(), "HmacSHA256")

private fun AuthorizeHttpRequestsRegistry.applyScopeRules() {
    requestMatchers(HttpMethod.OPTIONS, "/**")
        .permitAll()
        .requestMatchers(HttpMethod.GET, "/api/v1/games/**")
        .hasAuthority("SCOPE_games:read")
        .requestMatchers(HttpMethod.POST, "/api/v1/bets")
        .hasAuthority("SCOPE_bets:write")
        .anyRequest()
        .permitAll()
}

private fun OAuth2ResourceServerConfigurer<HttpSecurity>.applyJwtWithJsonErrors() {
    jwt(Customizer.withDefaults())
        .authenticationEntryPoint { _, response, _ ->
            writeSecurityError(response, 401, "AUTHENTICATION_REQUIRED", "A valid bearer token is required")
        }.accessDeniedHandler { _, response, _ ->
            writeSecurityError(response, 403, "INSUFFICIENT_SCOPE", "The required API scope is missing")
        }
}

private fun writeSecurityError(
    response: HttpServletResponse,
    status: Int,
    code: String,
    message: String,
) {
    response.status = status
    response.contentType = "application/json"
    response.characterEncoding = Charsets.UTF_8.name()
    response.writer.write(
        """{"status":$status,"code":"$code","message":"$message"}""",
    )
}
