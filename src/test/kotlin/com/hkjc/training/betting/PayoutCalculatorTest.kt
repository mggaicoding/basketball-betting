package com.hkjc.training.betting

import com.hkjc.training.betting.service.PayoutCalculator
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal

class PayoutCalculatorTest :
    FunSpec({
        val calculator = PayoutCalculator()

        test("a winning bet returns stake times odds") {
            calculator.potentialPayout(BigDecimal("100"), BigDecimal("1.85")) shouldBe
                BigDecimal("185.00")
        }

        test("the amount is rounded HALF_UP to two decimals") {
            // 33.33 * 1.85 = 61.6605, which rounds up at the third decimal.
            calculator.potentialPayout(BigDecimal("33.33"), BigDecimal("1.85")) shouldBe
                BigDecimal("61.66")
            // 10 * 1.005 = 10.05 exactly at the midpoint, so HALF_UP goes away from zero.
            calculator.potentialPayout(BigDecimal("10"), BigDecimal("1.005")) shouldBe
                BigDecimal("10.05")
        }

        test("a stake of zero or less is rejected, naming the stake") {
            listOf("0", "-1").forEach { stake ->
                shouldThrow<IllegalArgumentException> {
                    calculator.potentialPayout(BigDecimal(stake), BigDecimal("1.85"))
                }.message shouldContain "stake"
            }
        }

        test("odds of zero or less are rejected, naming the odds") {
            listOf("0", "-1.5").forEach { odds ->
                shouldThrow<IllegalArgumentException> {
                    calculator.potentialPayout(BigDecimal("100"), BigDecimal(odds))
                }.message shouldContain "odds"
            }
        }

        test("a payout over one million is rejected rather than capped") {
            shouldThrow<IllegalArgumentException> {
                calculator.potentialPayout(BigDecimal("500000"), BigDecimal("2.01"))
            }.message shouldContain "exceeds the maximum"
        }

        test("a payout of exactly one million is allowed") {
            calculator.potentialPayout(BigDecimal("500000"), BigDecimal("2")) shouldBe
                BigDecimal("1000000.00")
        }
    })
