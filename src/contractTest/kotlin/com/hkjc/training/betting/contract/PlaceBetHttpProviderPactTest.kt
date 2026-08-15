package com.hkjc.training.betting.contract

import au.com.dius.pact.provider.IHttpClientFactory
import au.com.dius.pact.provider.IProviderInfo
import au.com.dius.pact.provider.junit5.HttpTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.Date

/**
 * Replays every interaction against the application started by [SpringBootTest], so the security
 * chain, validation, controller and serialisation are all in the loop. [TestTemplate] generates
 * one test per interaction found in the pact.
 */
@Provider(PROVIDER_NAME)
// Scoped to one consumer: the BetPlaced message pact names the same provider and is
// verified by BetPlacedMessageProviderPactTest instead.
@Consumer(HTTP_CONSUMER_NAME)
@PactFolder("build/pacts")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
open class PlaceBetHttpProviderPactTest {
    @LocalServerPort
    private var port: Int = 0

    @BeforeEach
    open fun setTarget(context: PactVerificationContext?) {
        // The contract carries a placeholder token; the provider swaps in a signed one so the
        // security chain still runs. `@TargetRequestFilter` looks like the hook for this but is
        // only read by the JUnit 4 runner, so supplying the HTTP client is the way here.
        context?.target =
            HttpTestTarget("localhost", port, "/") {
                object : IHttpClientFactory {
                    override fun newClient(provider: IProviderInfo): CloseableHttpClient =
                        authenticatingClient()
                }
            }
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    open fun verifyPact(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    /** Nothing to arrange, but the method must exist: an unhandled state fails verification. */
    @State("game G-100 is open for betting")
    open fun gameIsOpenForBetting() = Unit

    private fun authenticatingClient(): CloseableHttpClient =
        HttpClients
            .custom()
            .addRequestInterceptorFirst { request, _, _ ->
                request.removeHeaders("Authorization")
                request.addHeader("Authorization", "Bearer ${signedBetsWriteToken()}")
            }.build()

    private fun signedBetsWriteToken(): String {
        val issuedAt = Instant.now()
        val claims =
            JWTClaimsSet
                .Builder()
                .issuer("hkjc-training-local")
                .subject("pact-provider-verifier")
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plusSeconds(300)))
                .claim("scope", "bets:write")
                .build()

        return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
            .apply { sign(MACSigner(DEMO_SECRET.toByteArray())) }
            .serialize()
    }
}

/** Mirrors the classroom-only secret in SecurityConfig, which is `internal` to the main module. */
private const val DEMO_SECRET = "hkjc-training-local-secret-key-32-bytes"
