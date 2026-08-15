package com.hkjc.training.betting.service

import com.hkjc.training.betting.dto.GameResponse
import com.hkjc.training.betting.repository.GameRepository
import org.springframework.stereotype.Service
import com.hkjc.training.betting.exception.GameNotFoundException

@Service
class GameQueryService(
    private val repository: GameRepository,
) {
    fun getGame(gameId: String): GameResponse {
        val game = repository.findById(gameId) ?: throw GameNotFoundException(gameId)
        return GameResponse(
            gameId = game.id,
            homeTeam = game.homeTeam,
            awayTeam = game.awayTeam,
            startTime = game.startTime,
            homeOdds = game.homeOdds,
            awayOdds = game.awayOdds,
        )
    }
}
