package com.hkjc.training.betting

import com.hkjc.training.betting.domain.Bet
import com.hkjc.training.betting.enum.Selection
import com.hkjc.training.betting.repository.InMemoryBetRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class InMemoryBetRepositoryTest :
    FunSpec({
        test("each saved bet is given its own identifier") {
            val repository = InMemoryBetRepository()

            repository.save(bet()).id shouldBe "B-1"
            repository.save(bet()).id shouldBe "B-2"
        }

        test("saving keeps the bet as it was placed") {
            val placed = bet()

            val saved = InMemoryBetRepository().save(placed)

            saved shouldBe placed.copy(id = saved.id)
        }
    })

private fun bet() =
    Bet(
        gameId = "G-100",
        selection = Selection.HOME,
        stake = BigDecimal("100"),
        odds = BigDecimal("1.85"),
        placedAt = Instant.parse("2026-08-12T10:00:00Z"),
    )
