package com.hkjc.training.betting.messaging

import com.hkjc.training.betting.enum.Selection
import java.math.BigDecimal
import java.time.Instant

/** The business fact only; correlation metadata travels in the message header. */
data class BetPlacedEvent(
    val eventId: String,
    val eventType: String = "BetPlaced",
    val eventVersion: Int = 2,
    val occurredAt: Instant,
    val data: BetPlacedData,
)

data class BetPlacedData(
    val betId: String,
    val gameId: String,
    val selection: Selection,
    val stake: BigDecimal,
    val odds: BigDecimal,
)

