package com.hkjc.training.betting.controller

import com.hkjc.training.betting.dto.BetResponse
import com.hkjc.training.betting.dto.PlaceBetRequest
import com.hkjc.training.betting.exception.ApiError
import com.hkjc.training.betting.service.BetService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/bets")
class BetController(
    private val betService: BetService,
) {
    @Operation(
        summary = "Place a bet",
        description = "The customer is taken from the token subject, never from the request body.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Bet accepted"),
        ApiResponse(
            responseCode = "400",
            description = "Request body failed validation",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "401",
            description = "Bearer token missing or invalid",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Token lacks the bets:write scope",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Game does not exist",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    @PostMapping
    suspend fun placeBet(
        @Valid @RequestBody request: PlaceBetRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<BetResponse> =
        ResponseEntity.status(201).body(
            betService.placeBet(
                request = request,
                customerId = jwt.subject,
            ),
        )
}
