package com.hkjc.training.betting.contract

import au.com.dius.pact.consumer.MessagePactBuilder
import au.com.dius.pact.consumer.dsl.LambdaDsl
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt
import au.com.dius.pact.consumer.junit5.PactTestFor
import au.com.dius.pact.consumer.junit5.ProviderType
import au.com.dius.pact.core.model.PactSpecVersion
import au.com.dius.pact.core.model.annotations.Pact
import au.com.dius.pact.core.model.messaging.Message
import au.com.dius.pact.core.model.messaging.MessagePact
import com.hkjc.training.betting.messaging.NotificationBetPlacedConsumer
import com.hkjc.training.betting.messaging.BetPlacedEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import kotlin.test.assertEquals

/**
 * Stands in for a downstream notification service. It runs the real deserialiser and the real handler,
 * so a contract is only published if the consumer can actually process the message. No broker is
 * involved: a message pact fixes the shape of the event, not its delivery.
 */
@ExtendWith(PactConsumerTestExt::class)
@PactTestFor(
    providerName = PROVIDER_NAME,
    providerType = ProviderType.ASYNCH,
    pactVersion = PactSpecVersion.V3,
)
class BetPlacedMessageConsumerPactTest {
    @Pact(consumer = MESSAGE_CONSUMER_NAME)
    fun betPlacedEvent(builder: MessagePactBuilder): MessagePact =
        builder
            .given("a bet has been accepted")
            .expectsToReceive("a BetPlaced event")
            .withContent(
                LambdaDsl
                    .newJsonBody { body ->
                        body.stringType("eventId", "event-301")
                        body.`object`("data") { data ->
                            data.stringType("betId", "B-201")
                            // Only here because the deserialiser refuses to construct the event
                            // without them: a contract is as wide as what you need to parse.
                            data.stringType("gameId", "G-100")
                            data.stringType("selection", "HOME")
                            data.decimalType("stake", 100.0)
                            data.decimalType("odds", 1.85)
                        }
                        body.datetime("occurredAt", "yyyy-MM-dd'T'HH:mm:ss'Z'")
                    }.build(),
            ).toPact()

    @Test
    @PactTestFor(pactMethod = "betPlacedEvent")
    fun `the notification handler can process the event`(messages: List<Message>) {
        val payload = messages.single().contentsAsBytes()

        val event = jsonMapper.readValue(payload, BetPlacedEvent::class.java)
        NotificationBetPlacedConsumer().accept(event)

        assertEquals("B-201", event.data.betId)
    }

    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .build()
}

internal const val MESSAGE_CONSUMER_NAME = "bet-notification-service"
