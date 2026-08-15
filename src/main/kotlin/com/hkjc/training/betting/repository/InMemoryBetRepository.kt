package com.hkjc.training.betting.repository

import com.hkjc.training.betting.domain.Bet
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Repository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Keeps bets for the lifetime of the process, so the API behaves the same without a database. */
@Repository
@Profile("!database")
class InMemoryBetRepository : BetRepository {
    private val sequence = AtomicLong(0)
    private val bets = ConcurrentHashMap<String, Bet>()

    override fun save(bet: Bet): Bet {
        val stored = bet.copy(id = "B-${sequence.incrementAndGet()}")
        bets[stored.id] = stored
        return stored
    }

    override fun findById(betId: String): Bet? = bets[betId]
}
