package com.hkjc.training.betting.controller

import com.hkjc.training.betting.dto.GameResponse
import com.hkjc.training.betting.exception.ApiError
import com.hkjc.training.betting.service.GameQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/games")
class GameController(
    private val gameQueryService: GameQueryService,
) {
    @Operation(summary = "Read one game with its current odds")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Game found"),
        ApiResponse(
            responseCode = "401",
            description = "Bearer token missing or invalid",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "403",
            description = "Token lacks the games:read scope",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
        ApiResponse(
            responseCode = "404",
            description = "Game does not exist",
            content = [Content(schema = Schema(implementation = ApiError::class))],
        ),
    )
    @GetMapping("/{gameId}")
    fun getGame(
        @PathVariable gameId: String,
    ): GameResponse = gameQueryService.getGame(gameId)
}
