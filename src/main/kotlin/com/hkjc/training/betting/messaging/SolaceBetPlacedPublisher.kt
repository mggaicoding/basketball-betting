package com.hkjc.training.betting.messaging

import com.hkjc.training.betting.configuration.TraceIdFilter
import org.slf4j.LoggerFactory
import org.springframework.cloud.stream.function.StreamBridge
import org.springframework.context.annotation.Profile
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Component

@Component
@Profile("solace")
class SolaceBetPlacedPublisher(
    private val streamBridge: StreamBridge,
) : BetPlacedPublisher {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publish(event: BetPlacedEvent) {
        val message: Message<BetPlacedEvent> =
            MessageBuilder
                .withPayload(event)
                .setHeader(TraceIdFilter.TRACE_ID_HEADER, TraceIdFilter.currentTraceId())
                .build()

        check(streamBridge.send("betPlaced-out-0", message)) {
            "BetPlaced was not accepted by the Solace output binding"
        }
        logger.info(
            "event published eventId={} betId={} destination=betPlaced-out-0",
            event.eventId,
            event.data.betId,
        )
    }
}
