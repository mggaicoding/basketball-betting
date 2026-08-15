package com.hkjc.training.betting.dto

import com.hkjc.training.betting.enum.BetStatus
import java.math.BigDecimal

data class BetResponse(
    val betId: String,
    val odds: BigDecimal,
    val status: BetStatus,
)
