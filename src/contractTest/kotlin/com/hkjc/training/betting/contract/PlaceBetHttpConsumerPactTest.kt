package com.hkjc.training.betting.contract

import au.com.dius.pact.consumer.MockServer
import au.com.dius.pact.consumer.dsl.LambdaDsl
import au.com.dius.pact.consumer.dsl.PactDslWithProvider
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.RequestResponsePact
import au.com.dius.pact.core.model.annotations.Pact
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.web.client.RestClient
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stands in for a downstream service that submits bets. Pact starts a mock server from the
 * expectations below and writes the contract only if this real client succeeds against it.
 *
 * The matchers are the point: `stringValue` pins an exact value, `stringType` and `decimalType`
 * pin only the type, and undeclared fields are ignored — so adding a field is compatible and
 * renaming one is not.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(providerName = PROVIDER_NAME, pactMethod = "acceptedHomeBet", pactVersion = PactSpecVersion.V3)
class PlaceBetHttpConsumerPactTest {
    @Pact(consumer = HTTP_CONSUMER_NAME)
    fun acceptedHomeBet(builder: PactDslWithProvider): RequestResponsePact =
        builder
            .given("game G-100 is open for betting")
            .uponReceiving("a valid HOME bet")
            .path("/api/v1/bets")
            .method("POST")
            .headers(
                mapOf(
                    "Content-Type" to "application/json",
                    "Authorization" to "Bearer $CONTRACT_TEST_TOKEN",
                ),
            ).body(
                LambdaDsl
                    .newJsonBody { body ->
                        body.stringValue("gameId", "G-100")
                        body.stringValue("selection", "HOME")
                        body.numberType("stake", 100)
                    }.build(),
            ).willRespondWith()
            .status(201)
            .headers(mapOf("Content-Type" to "application/json"))
            .body(
                LambdaDsl
                    .newJsonBody { body ->
                        body.stringType("betId", "B-201")
                        body.decimalType("odds", 1.85)
                        body.stringValue("status", "ACCEPTED")
                    }.build(),
            ).toPact()

    @Test
    fun `a downstream client can place a bet and read the response`(mockServer: MockServer) {
        val response =
            RestClient
                .create()
                .post()
                .uri("${mockServer.getUrl()}/api/v1/bets")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $CONTRACT_TEST_TOKEN")
                .body("""{"gameId":"G-100","selection":"HOME","stake":100}""")
                .retrieve()
                .body(Map::class.java)!!

        assertEquals("ACCEPTED", response["status"])
        assertTrue((response["betId"] as String).isNotBlank())
    }
}

internal const val PROVIDER_NAME = "betting-api"
internal const val HTTP_CONSUMER_NAME = "betting-training-consumer"
internal const val CONTRACT_TEST_TOKEN = "contract-test-token"
