package com.hkjc.training.betting.service

import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * What a customer stands to win.
 *
 * The bet confirmation screen has to tell the customer what the bet returns if it wins.
 * Today the API answers with the bet identifier, the odds and the status, so the client
 * is left to multiply two numbers and then guess at the rounding and at the house limit.
 * Both of those are the sportsbook's decisions and belong on this side of the boundary,
 * or two clients will eventually show two different numbers for the same bet.
 *
 * Demo 1 starts here: the rules are the comment below, the body is a TODO.
 */
@Service
class PayoutCalculator {
    // Return what this bet would pay out if it wins.
    //
    // - The payout is stake * odds, rounded HALF_UP to 2 decimal places, because the
    //   customer is shown a money amount and money has two decimals.
    // - Stake and odds must both be greater than zero; reject anything else with
    //   IllegalArgumentException, naming which argument was wrong.
    // - A single bet may not pay out more than 1,000,000. Reject a bet that would
    //   exceed it rather than silently capping the number, because a capped payout is
    //   a promise the house never made.
    fun potentialPayout(
        stake: BigDecimal,
        odds: BigDecimal,
    ): BigDecimal = TODO("Demo 1: implement from the comment above")
}
