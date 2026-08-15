package com.hkjc.training.betting.exception

class GameNotFoundException(gameId: String) : RuntimeException("Game $gameId was not found")
