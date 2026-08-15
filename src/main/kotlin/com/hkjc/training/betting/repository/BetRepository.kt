package com.hkjc.training.betting.repository

import com.hkjc.training.betting.domain.Bet

/** Stores accepted bets. One port, two adapters selected by Spring profile. */
interface BetRepository {
    fun save(bet: Bet): Bet

    fun findById(betId: String): Bet?
}
