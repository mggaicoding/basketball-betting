package com.hkjc.training.betting.controller

import com.hkjc.training.betting.dto.BetResponse
import com.hkjc.training.betting.dto.PlaceBetRequest
import com.hkjc.training.betting.service.BetService
import io.swagger.v3.oas.annotations.Operation
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bets")
class BetController(
    private val betService: BetService,
) {
    @Operation(summary = "Place a bet")
    @PostMapping
    suspend fun placeBet(
        @Valid @RequestBody request: PlaceBetRequest,
        @RequestHeader("X-Customer-Id") customerId: String,
    ): ResponseEntity<BetResponse> =
        ResponseEntity.status(201).body(
            betService.placeBet(
                request = request,
                customerId = customerId,
            ),
        )
}
