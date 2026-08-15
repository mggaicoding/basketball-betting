package com.hkjc.training.betting.messaging

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class NotificationBetPlacedConsumer {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun accept(event: BetPlacedEvent) {
        logger.info(
            "event notified eventId={} betId={} gameId={}",
            event.eventId,
            event.data.betId,
            event.data.gameId,
        )
    }
}
