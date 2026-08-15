package com.hkjc.training.betting.service

import com.hkjc.training.betting.dto.BetDetailResponse
import com.hkjc.training.betting.exception.BetNotFoundException
import com.hkjc.training.betting.repository.BetRepository
import org.springframework.stereotype.Service

@Service
class BetQueryService(
    private val betRepository: BetRepository,
    private val payoutCalculator: PayoutCalculator,
) {
    /**
     * Someone else's bet is reported as missing rather than forbidden: a 403 would confirm
     * that the identifier exists, which is itself information the caller has no right to.
     */
    fun getBet(
        betId: String,
        customerId: String,
    ): BetDetailResponse {
        val bet = betRepository.findById(betId)
        if (bet == null || bet.customerId != customerId) {
            throw BetNotFoundException(betId)
        }
        return BetDetailResponse(
            betId = bet.id,
            gameId = bet.gameId,
            selection = bet.selection,
            stake = bet.stake,
            odds = bet.odds,
            potentialPayout = payoutCalculator.potentialPayout(bet.stake, bet.odds),
            status = bet.status,
            placedAt = bet.placedAt,
        )
    }
}
