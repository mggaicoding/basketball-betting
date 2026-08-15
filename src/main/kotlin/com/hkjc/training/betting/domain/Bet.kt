package com.hkjc.training.betting.domain

import com.hkjc.training.betting.enum.BetStatus
import com.hkjc.training.betting.enum.Selection
import java.math.BigDecimal
import java.time.Instant

data class Bet(
    val id: String = "",
    val gameId: String,
    val selection: Selection,
    val stake: BigDecimal,
    val odds: BigDecimal,
    val placedAt: Instant,
    val status: BetStatus = BetStatus.ACCEPTED,
)
