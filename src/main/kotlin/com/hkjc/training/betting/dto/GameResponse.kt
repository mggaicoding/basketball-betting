package com.hkjc.training.betting.dto

import java.math.BigDecimal
import java.time.Instant

data class GameResponse(
    val gameId: String,
    val homeTeam: String,
    val awayTeam: String,
    val startTime: Instant,
    val homeOdds: BigDecimal,
    val awayOdds: BigDecimal,
)
