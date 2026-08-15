package com.hkjc.training.betting.repository

import com.hkjc.training.betting.domain.Bet
import com.hkjc.training.betting.enum.BetStatus
import com.hkjc.training.betting.enum.Selection
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
@Profile("database")
class JdbcBetRepository(
    private val jdbcClient: JdbcClient,
) : BetRepository {
    override fun save(bet: Bet): Bet {
        val stored = bet.copy(id = UUID.randomUUID().toString())
        val inserted =
            jdbcClient
                .sql(
                    """
                    insert into bet (
                        id, customer_id, game_id, selection, stake, odds, status, created_at
                    ) values (
                        :id, :customerId, :gameId, :selection, :stake, :odds, :status, :placedAt
                    )
                    """.trimIndent(),
                ).params(
                    mapOf(
                        "id" to stored.id,
                        "customerId" to stored.customerId,
                        "gameId" to stored.gameId,
                        "selection" to stored.selection.name,
                        "stake" to stored.stake,
                        "odds" to stored.odds,
                        "status" to stored.status.name,
                        // The driver cannot infer a SQL type for java.time.Instant.
                        "placedAt" to OffsetDateTime.ofInstant(stored.placedAt, ZoneOffset.UTC),
                    ),
                ).update()
        check(inserted == 1) { "Expected one inserted bet but inserted $inserted" }
        return stored
    }

    override fun findById(betId: String): Bet? =
        jdbcClient
            .sql(
                """
                select id, customer_id, game_id, selection, stake, odds, status, created_at
                from bet
                where id = :betId
                """.trimIndent(),
            ).param("betId", betId)
            .query { rs, _ -> rs.toBet() }
            .optional()
            .orElse(null)
}

private fun ResultSet.toBet() =
    Bet(
        id = getString("id"),
        customerId = getString("customer_id"),
        gameId = getString("game_id"),
        selection = Selection.valueOf(getString("selection")),
        stake = getBigDecimal("stake"),
        odds = getBigDecimal("odds"),
        placedAt = getTimestamp("created_at").toInstant(),
        status = BetStatus.valueOf(getString("status")),
    )
