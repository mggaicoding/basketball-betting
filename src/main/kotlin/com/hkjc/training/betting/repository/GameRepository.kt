package com.hkjc.training.betting.repository

import com.hkjc.training.betting.domain.Game

/** Reads the games a bet can be placed on. One port, two adapters selected by Spring profile. */
interface GameRepository {
    fun findById(gameId: String): Game?
}
