package com.hkjc.training.betting

import com.hkjc.training.betting.client.OddsClient
import com.hkjc.training.betting.client.RiskAssessment
import com.hkjc.training.betting.client.RiskClient
import com.hkjc.training.betting.client.ValidatedOdds
import com.hkjc.training.betting.domain.Bet
import com.hkjc.training.betting.domain.Game
import com.hkjc.training.betting.dto.PlaceBetRequest
import com.hkjc.training.betting.enum.Selection
import com.hkjc.training.betting.exception.GameNotFoundException
import com.hkjc.training.betting.exception.InvalidBetException
import com.hkjc.training.betting.messaging.BetPlacedPublisher
import com.hkjc.training.betting.repository.BetRepository
import com.hkjc.training.betting.service.BetService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The service boundary: no Spring context, no HTTP, no database. MockK controls the two
 * external validations and the repository, so each test costs milliseconds.
 */
class BetServiceTest :
    FunSpec({
        val repository = mockk<BetRepository>()
        val publisher = mockk<BetPlacedPublisher>(relaxed = true)
        val oddsClient = mockk<OddsClient>()
        val riskClient = mockk<RiskClient>()
        val clock = Clock.fixed(PLACED_AT, ZoneOffset.UTC)
        val service = BetService(repository, publisher, oddsClient, riskClient, clock)

        // Shared mocks would otherwise leak call counts between tests.
        beforeTest { clearAllMocks() }

        test("unknown game raises the domain exception") {
            coEvery { oddsClient.validateSelection("G-404", Selection.HOME) } throws
                GameNotFoundException("G-404")
            coEvery { riskClient.assessCustomer(any(), any()) } returns RiskAssessment(approved = true)

            shouldThrow<GameNotFoundException> {
                service.placeBet(
                    request = request(gameId = "G-404", selection = Selection.HOME),
                    customerId = "C-100",
                )
            }

            coVerify(exactly = 1) { oddsClient.validateSelection("G-404", Selection.HOME) }
        }

        test("HOME selects home odds and maps the saved bet") {
            coEvery { oddsClient.validateSelection("G-100", Selection.HOME) } returns
                ValidatedOdds(game(), BigDecimal("1.85"))
            coEvery { riskClient.assessCustomer("C-100", BigDecimal("100")) } returns
                RiskAssessment(approved = true)
            every { repository.save(any()) } answers { firstArg<Bet>().copy(id = "B-101") }

            val response =
                service.placeBet(
                    request = request(gameId = "G-100", selection = Selection.HOME),
                    customerId = "C-100",
                )

            response.betId shouldBe "B-101"
            response.odds shouldBe BigDecimal("1.85")
            coVerify(exactly = 1) { oddsClient.validateSelection("G-100", Selection.HOME) }
            coVerify(exactly = 1) { riskClient.assessCustomer("C-100", BigDecimal("100")) }
            verify(exactly = 1) {
                repository.save(match { it.selection == Selection.HOME && it.placedAt == PLACED_AT })
            }
        }

        /**
         * Concurrency as a regression assertion rather than something read off a stopwatch:
         * coroutine virtual time makes the 800 ms and 600 ms waits overlap observably.
         * Sequential code would report 1400.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        test("placeBet overlaps the independent odds and risk requests") {
            every { repository.save(any()) } answers { firstArg<Bet>().copy(id = "B-100") }
            val slowClients =
                BetService(
                    betRepository = repository,
                    clock = clock,
                    publisher = publisher,
                    oddsClient =
                        OddsClient { _, _ ->
                            delay(800)
                            ValidatedOdds(game(), BigDecimal("1.85"))
                        },
                    riskClient =
                        RiskClient { _, _ ->
                            delay(600)
                            RiskAssessment(approved = true)
                        },
                )

            runTest {
                slowClients.placeBet(
                    request = request(gameId = "G-100", selection = Selection.HOME),
                    customerId = "C-100",
                )

                currentTime shouldBe 800
            }
        }

        // === Demo Unit Test — uncomment to prove the stake rule at the service boundary ===
        // Both validations are stubbed so that, without the rule, the call would simply succeed:
        // the red then reads "no exception was thrown" rather than a mock error.
        // test("stake above 1000 raises InvalidBetException") {
        //     coEvery { oddsClient.validateSelection("G-100", Selection.HOME) } returns
        //         ValidatedOdds(game(), BigDecimal("1.85"))
        //     coEvery { riskClient.assessCustomer(any(), any()) } returns RiskAssessment(approved = true)
        //     every { repository.save(any()) } answers { firstArg<Bet>().copy(id = "B-999") }
        //
        //     shouldThrow<InvalidBetException> {
        //         service.placeBet(
        //             request = request("G-100", Selection.HOME, BigDecimal("1001")),
        //             customerId = "C-100",
        //         )
        //     }
        //
        //     // The rule must reject before any I/O happens.
        //     coVerify(exactly = 0) { oddsClient.validateSelection(any(), any()) }
        //     verify(exactly = 0) { repository.save(any()) }
        // }
        // === end ===
    })

private fun request(
    gameId: String,
    selection: Selection,
    stake: BigDecimal = BigDecimal("100"),
) = PlaceBetRequest(gameId = gameId, selection = selection, stake = stake)

private fun game() =
    Game(
        id = "G-100",
        homeTeam = "Hong Kong Lions",
        awayTeam = "Falcons",
        startTime = Instant.parse("2026-08-04T12:00:00Z"),
        homeOdds = BigDecimal("1.85"),
        awayOdds = BigDecimal("2.05"),
    )

private val PLACED_AT: Instant = Instant.parse("2026-08-12T10:00:00Z")
