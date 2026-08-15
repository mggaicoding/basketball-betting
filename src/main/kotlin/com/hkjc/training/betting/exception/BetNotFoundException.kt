package com.hkjc.training.betting.exception

class BetNotFoundException(
    betId: String,
) : RuntimeException("Bet $betId was not found")
