package com.hkjc.training.betting.domain

import java.math.BigDecimal
import java.time.Instant

data class Game(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val startTime: Instant,
    val homeOdds: BigDecimal,
    val awayOdds: BigDecimal,
)
