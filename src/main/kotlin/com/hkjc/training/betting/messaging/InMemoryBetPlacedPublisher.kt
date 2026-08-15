package com.hkjc.training.betting.messaging

import com.hkjc.training.betting.configuration.TraceIdFilter
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

@Component
@Profile("!solace")
class InMemoryBetPlacedPublisher(
    private val notificationConsumer: NotificationBetPlacedConsumer,
) : BetPlacedPublisher {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val events = CopyOnWriteArrayList<BetPlacedEvent>()

    override fun publish(event: BetPlacedEvent) {
        val traceId = TraceIdFilter.currentTraceId()
        events += event
        logger.info(
            "event published eventId={} betId={} destination=in-memory",
            event.eventId,
            event.data.betId,
        )
        // Same thread, so the MDC already carries the trace.
        TraceIdFilter.withTraceId(traceId) { notificationConsumer.accept(event) }
    }

    fun publishedEvents(): List<BetPlacedEvent> = events.toList()
}
