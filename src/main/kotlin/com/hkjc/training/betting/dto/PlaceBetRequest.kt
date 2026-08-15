package com.hkjc.training.betting.dto

import com.hkjc.training.betting.enum.Selection
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

data class PlaceBetRequest(
    @field:NotBlank
    val gameId: String,
    val selection: Selection,
    @field:DecimalMin("0.01")
    val stake: BigDecimal,
)
