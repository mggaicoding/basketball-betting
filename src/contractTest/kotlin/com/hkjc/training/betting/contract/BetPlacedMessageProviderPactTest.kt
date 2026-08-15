package com.hkjc.training.betting.contract

import au.com.dius.pact.provider.MessageAndMetadata
import au.com.dius.pact.provider.PactVerifyProvider
import au.com.dius.pact.provider.junit5.MessageTestTarget
import au.com.dius.pact.provider.junit5.PactVerificationContext
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider
import au.com.dius.pact.provider.junitsupport.Consumer
import au.com.dius.pact.provider.junitsupport.Provider
import au.com.dius.pact.provider.junitsupport.State
import au.com.dius.pact.provider.junitsupport.loader.PactFolder
import com.hkjc.training.betting.enum.Selection
import com.hkjc.training.betting.messaging.BetPlacedData
import com.hkjc.training.betting.messaging.BetPlacedEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.ExtendWith
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Builds the event with the production [BetPlacedEvent] and mapper, so renaming a field breaks
 * this test — the regression the contract exists to catch. No broker and no HTTP involved.
 */
@Provider(PROVIDER_NAME)
@Consumer(MESSAGE_CONSUMER_NAME)
@PactFolder("build/pacts")
open class BetPlacedMessageProviderPactTest {
    @BeforeEach
    open fun setTarget(context: PactVerificationContext?) {
        context?.target = MessageTestTarget(listOf(this::class.java.packageName))
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider::class)
    open fun verifyPact(context: PactVerificationContext?) {
        context?.verifyInteraction()
    }

    @State("a bet has been accepted")
    open fun betHasBeenAccepted() = Unit

    @PactVerifyProvider("a BetPlaced event")
    open fun produceBetPlacedEvent(): MessageAndMetadata {
        val event =
            BetPlacedEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = Instant.parse("2026-08-04T12:00:00Z"),
                data =
                    BetPlacedData(
                        betId = "B-201",
                        gameId = "G-100",
                        selection = Selection.HOME,
                        stake = BigDecimal("100.00"),
                        odds = BigDecimal("1.85"),
                    ),
            )
        return MessageAndMetadata(jsonMapper.writeValueAsBytes(event), emptyMap())
    }

    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(KotlinModule.Builder().build())
            .build()
}
