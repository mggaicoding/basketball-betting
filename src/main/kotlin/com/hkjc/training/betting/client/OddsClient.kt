package com.hkjc.training.betting.client

import com.hkjc.training.betting.domain.Game
import com.hkjc.training.betting.enum.Selection
import com.hkjc.training.betting.exception.GameNotFoundException
import com.hkjc.training.betting.repository.GameRepository
import kotlinx.coroutines.future.await
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

data class ValidatedOdds(
    val game: Game,
    val odds: BigDecimal,
)

/** Prices are owned elsewhere and move between listing a game and placing a bet. */
fun interface OddsClient {
    suspend fun validateSelection(
        gameId: String,
        selection: Selection,
    ): ValidatedOdds
}

@Component
@Profile("!test")
class HttpOddsClient(
    @Value("\${training.clients.odds.base-url}") baseUrl: String,
    private val objectMapper: ObjectMapper,
) : OddsClient {
    private val httpClient = HttpClient.newHttpClient()
    private val baseUrl = baseUrl.trimEnd('/')

    override suspend fun validateSelection(
        gameId: String,
        selection: Selection,
    ): ValidatedOdds {
        val request =
            HttpRequest
                .newBuilder(
                    URI.create(
                        "$baseUrl/api/odds/validate" +
                            "?gameId=${gameId.urlEncoded()}&selection=${selection.name}",
                    ),
                ).timeout(Duration.ofSeconds(3))
                .GET()
                .build()
        val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()

        if (response.statusCode() == 404) {
            throw GameNotFoundException(gameId)
        }
        check(response.statusCode() in 200..299) {
            "Odds service returned HTTP ${response.statusCode()}"
        }
        return objectMapper.readValue(response.body(), OddsHttpResponse::class.java).toValidatedOdds()
    }
}

private fun OddsHttpResponse.toValidatedOdds() =
    ValidatedOdds(
        game =
            Game(
                id = gameId,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                startTime = scheduledStart,
                homeOdds = homeOdds,
                awayOdds = awayOdds,
            ),
        odds = acceptedOdds,
    )

/** Test-only adapter; the runtime uses the HTTP client above. */
@Component
@Profile("test")
class DeterministicOddsClient(
    private val gameRepository: GameRepository,
) : OddsClient {
    override suspend fun validateSelection(
        gameId: String,
        selection: Selection,
    ): ValidatedOdds {
        val game = gameRepository.findById(gameId) ?: throw GameNotFoundException(gameId)
        val odds = if (selection == Selection.HOME) game.homeOdds else game.awayOdds
        return ValidatedOdds(game, odds)
    }
}

private data class OddsHttpResponse(
    val gameId: String,
    val homeTeam: String,
    val awayTeam: String,
    val scheduledStart: Instant,
    val homeOdds: BigDecimal,
    val awayOdds: BigDecimal,
    val acceptedOdds: BigDecimal,
)

private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
