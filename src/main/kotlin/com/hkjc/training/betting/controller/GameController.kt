package com.hkjc.training.betting.controller

import com.hkjc.training.betting.dto.GameResponse
import com.hkjc.training.betting.service.GameQueryService
import io.swagger.v3.oas.annotations.Operation
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
    @GetMapping("/{gameId}")
    fun getGame(
        @PathVariable gameId: String,
    ): GameResponse = gameQueryService.getGame(gameId)
}
