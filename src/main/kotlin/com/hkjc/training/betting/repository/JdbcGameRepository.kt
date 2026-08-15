package com.hkjc.training.betting.repository

import com.hkjc.training.betting.domain.Game
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
@Profile("database")
class JdbcGameRepository(
    private val jdbcClient: JdbcClient,
) : GameRepository {
    override fun findById(gameId: String): Game? =
        jdbcClient
            .sql(
                """
                select id, home_team, away_team, start_time, home_odds, away_odds
                from game
                where id = :gameId
                """.trimIndent(),
            ).param("gameId", gameId)
            .query { rs, _ -> rs.toGame() }
            .optional()
            .orElse(null)
}

private fun ResultSet.toGame() =
    Game(
        id = getString("id"),
        homeTeam = getString("home_team"),
        awayTeam = getString("away_team"),
        startTime = getTimestamp("start_time").toInstant(),
        homeOdds = getBigDecimal("home_odds"),
        awayOdds = getBigDecimal("away_odds"),
    )
