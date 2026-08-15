package com.hkjc.training.betting.dto

import com.hkjc.training.betting.enum.BetStatus
import com.hkjc.training.betting.enum.Selection
import java.math.BigDecimal
import java.time.Instant

/** No customer identifier: the caller already knows it is theirs, and nobody else may read it. */
data class BetDetailResponse(
    val betId: String,
    val gameId: String,
    val selection: Selection,
    val stake: BigDecimal,
    val odds: BigDecimal,
    val potentialPayout: BigDecimal,
    val status: BetStatus,
    val placedAt: Instant,
)
