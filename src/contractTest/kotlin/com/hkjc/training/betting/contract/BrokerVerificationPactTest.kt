package com.hkjc.training.betting.contract

import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.loader.PactBroker
import au.com.dius.pact.provider.junitsupport.loader.PactBrokerAuth
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * The same two verifications sourced from PactFlow. Separate classes because Pact refuses to
 * start a class carrying both `@PactFolder` and `@PactBroker`, and because only a broker-sourced
 * verification can publish its result back — which is what `can-i-deploy` reads.
 *
 * Run through `./gradlew pactBrokerVerify`.
 */
@Provider(PROVIDER_NAME)
@Consumer(HTTP_CONSUMER_NAME)
@PactBroker(
    url = "\${pactbroker.url}",
    authentication = PactBrokerAuth(token = "\${pactbroker.auth.token}"),
)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PlaceBetHttpBrokerPactTest : PlaceBetHttpProviderPactTest()

@Provider(PROVIDER_NAME)
@Consumer(MESSAGE_CONSUMER_NAME)
@PactBroker(
    url = "\${pactbroker.url}",
    authentication = PactBrokerAuth(token = "\${pactbroker.auth.token}"),
)
class BetPlacedMessageBrokerPactTest : BetPlacedMessageProviderPactTest()
