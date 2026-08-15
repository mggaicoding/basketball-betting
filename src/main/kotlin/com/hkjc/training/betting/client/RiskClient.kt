package com.hkjc.training.betting.client

import kotlinx.coroutines.future.await
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class RiskAssessment(
    val approved: Boolean,
)

fun interface RiskClient {
    suspend fun assessCustomer(
        customerId: String,
        stake: BigDecimal,
    ): RiskAssessment
}

@Component
@Profile("!test")
class HttpRiskClient(
    @Value("\${training.clients.risk.base-url}") baseUrl: String,
    private val objectMapper: ObjectMapper,
) : RiskClient {
    private val httpClient = HttpClient.newHttpClient()
    private val endpoint = URI.create("${baseUrl.trimEnd('/')}/api/risk/assessments")

    override suspend fun assessCustomer(
        customerId: String,
        stake: BigDecimal,
    ): RiskAssessment {
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(RiskHttpRequest(customerId, stake)),
                    ),
                ).build()
        val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()

        check(response.statusCode() in 200..299) {
            "Risk service returned HTTP ${response.statusCode()}"
        }
        return objectMapper.readValue(response.body(), RiskAssessment::class.java)
    }
}

/** Test-only adapter; the runtime uses the HTTP client above. */
@Component
@Profile("test")
class DeterministicRiskClient : RiskClient {
    override suspend fun assessCustomer(
        customerId: String,
        stake: BigDecimal,
    ) = RiskAssessment(approved = true)
}

private data class RiskHttpRequest(
    val customerId: String,
    val stake: BigDecimal,
)
