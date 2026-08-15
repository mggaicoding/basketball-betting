package com.hkjc.training.betting.service

import com.hkjc.training.betting.client.OddsClient
import com.hkjc.training.betting.client.RiskAssessment
import com.hkjc.training.betting.client.RiskClient
import com.hkjc.training.betting.client.ValidatedOdds
import com.hkjc.training.betting.domain.Bet
import com.hkjc.training.betting.dto.BetResponse
import com.hkjc.training.betting.dto.PlaceBetRequest
import com.hkjc.training.betting.exception.InvalidBetException
import com.hkjc.training.betting.messaging.BetPlacedData
import com.hkjc.training.betting.messaging.BetPlacedEvent
import com.hkjc.training.betting.messaging.BetPlacedPublisher
import com.hkjc.training.betting.repository.BetRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class BetService(
    private val betRepository: BetRepository,
    private val publisher: BetPlacedPublisher,
    private val oddsClient: OddsClient,
    private val riskClient: RiskClient,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** MDCContext carries the trace identifier across the coroutine's thread switches. */
    suspend fun placeBet(
        request: PlaceBetRequest,
        customerId: String,
    ): BetResponse = withContext(MDCContext()) {
        val validatedOdds = validateBet(request, customerId)
        val bet = saveBet(request, customerId, validatedOdds)
        publishBetPlacedEvent(bet)

        BetResponse(
            betId = bet.id,
            odds = bet.odds,
            status = bet.status,
        )
    }

    /**
     * Odds and Risk are independent, so they run concurrently: the wait costs max(800, 600) ms
     * rather than their sum. Both start on the same thread — a coroutine yields its thread while
     * suspended instead of occupying one per task.
     */
    private suspend fun validateBet(
        request: PlaceBetRequest,
        customerId: String,
    ): ValidatedOdds {
        // === Demo TDD · step 2 (still red) — uncomment the rule; the red moves from 201 to 500 ===
        // The rule is enforced before any I/O: an over-limit bet must not pay for the Odds
        // and Risk round trips. Nothing maps InvalidBetException yet, so the fallback
        // handler answers 500 — correct logic is not yet the agreed contract.
        // if (request.stake > BigDecimal("1000")) {
        //     throw InvalidBetException("Stake must not exceed 1000")
        // }
        // === end ===

        val (validatedOdds, riskAssessment) =
            coroutineScope {
                val odds = async { validateOdds(request) }
                val risk = async { assessRisk(customerId, request) }

                logger.info("both validations launched, awaiting results")
                odds.await() to risk.await()
            }

        if (!riskAssessment.approved) {
            throw InvalidBetException("Customer risk assessment rejected the bet")
        }
        return validatedOdds
    }

    private suspend fun validateOdds(request: PlaceBetRequest): ValidatedOdds {
        logger.info("odds validation started")
        val result = oddsClient.validateSelection(request.gameId, request.selection)
        logger.info("odds validation returned odds={}", result.odds)
        return result
    }

    private suspend fun assessRisk(
        customerId: String,
        request: PlaceBetRequest,
    ): RiskAssessment {
        logger.info("risk assessment started")
        val result = riskClient.assessCustomer(customerId, request.stake)
        logger.info("risk assessment returned approved={}", result.approved)
        return result
    }

    private fun saveBet(
        request: PlaceBetRequest,
        customerId: String,
        validatedOdds: ValidatedOdds,
    ): Bet {
        val savedBet =
            betRepository.save(
                Bet(
                    customerId = customerId,
                    gameId = validatedOdds.game.id,
                    selection = request.selection,
                    stake = request.stake,
                    odds = validatedOdds.odds,
                    placedAt = Instant.now(clock),
                ),
            )

        logger.info(
            "bet accepted betId={} gameId={} selection={} stake={} odds={}",
            savedBet.id,
            savedBet.gameId,
            savedBet.selection,
            savedBet.stake,
            savedBet.odds,
        )
        return savedBet
    }

    private fun publishBetPlacedEvent(bet: Bet) {
        publisher.publish(
            BetPlacedEvent(
                eventId = UUID.randomUUID().toString(),
                occurredAt = Instant.now(clock),
                data =
                    BetPlacedData(
                        betId = bet.id,
                        gameId = bet.gameId,
                        selection = bet.selection,
                        stake = bet.stake,
                        odds = bet.odds,
                    ),
            ),
        )
    }
}
