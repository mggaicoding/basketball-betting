package com.hkjc.training.betting

import com.hkjc.training.betting.service.PayoutCalculator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

/**
 * Demo 1 continues here. The placeholder below only keeps the spec runnable before the
 * demo starts; it asserts nothing. The real work is the payout cases listed under it:
 * type a test name and let inline completion write the body.
 */
class PayoutCalculatorTest :
    FunSpec({
        val calculator = PayoutCalculator()

        // Placeholder. Delete it once the cases below are written.
        test("the spec is wired up") {
            calculator shouldBe calculator
        }

        // One test per case:
        //
        // - a winning bet returns stake * odds
        // - the amount is rounded to two decimals, HALF_UP
        // - a stake of zero or less is rejected, and the message names the stake
        // - odds of zero or less are rejected, and the message names the odds
        // - a payout over 1,000,000 is rejected rather than capped
        //
        // Start by typing:  test("a winning bet returns stake times odds") {
        //
        // The first one should read:
        //   calculator.potentialPayout(BigDecimal("100"), BigDecimal("1.85")) shouldBe
        //       BigDecimal("185.00")
    })
