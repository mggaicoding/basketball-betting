package com.hkjc.training.betting.repository

import com.hkjc.training.betting.domain.Game
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

/** The classroom catalogue, so the API answers without a database. */
@Repository
@Profile("!database")
class InMemoryGameRepository : GameRepository {
    private val games =
        mapOf(
            "G-100" to
                Game(
                    id = "G-100",
                    homeTeam = "Hong Kong Lions",
                    awayTeam = "Falcons",
                    startTime = Instant.parse("2026-08-04T12:00:00Z"),
                    homeOdds = BigDecimal("1.85"),
                    awayOdds = BigDecimal("2.05"),
                ),
        )

    override fun findById(gameId: String): Game? = games[gameId]
}
