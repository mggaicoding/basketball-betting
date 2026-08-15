package com.hkjc.training.betting.messaging

/** The adapter is chosen by the `solace` profile. */
fun interface BetPlacedPublisher {
    fun publish(event: BetPlacedEvent)
}
