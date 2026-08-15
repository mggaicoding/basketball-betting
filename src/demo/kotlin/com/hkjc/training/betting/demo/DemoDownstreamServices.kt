package com.hkjc.training.betting.demo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

fun main() {
    startOddsService()
    startRiskService()

    println("Prepared Odds service: http://localhost:8091")
    println("Prepared Risk service: http://localhost:8092")
    println("Leave this process running during the async demo. Press Ctrl+C to stop.")
    CountDownLatch(1).await()
}

private fun startOddsService() {
    val server = HttpServer.create(InetSocketAddress(8091), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/api/odds/validate") { exchange ->
        val query = exchange.requestURI.rawQuery.parseQuery()
        val gameId = query["gameId"]
        val selection = query["selection"]
        println("ODDS  started   gameId=$gameId selection=$selection")
        Thread.sleep(800)

        if (gameId != "G-100") {
            exchange.respond(404, """{"code":"GAME_NOT_FOUND"}""")
        } else {
            val acceptedOdds = if (selection == "AWAY") "2.05" else "1.85"
            exchange.respond(
                200,
                """
                {
                  "gameId": "G-100",
                  "homeTeam": "Hong Kong Lions",
                  "awayTeam": "Falcons",
                  "scheduledStart": "2026-08-04T12:00:00Z",
                  "homeOdds": 1.85,
                  "awayOdds": 2.05,
                  "acceptedOdds": $acceptedOdds
                }
                """.trimIndent(),
            )
        }
        println("ODDS  completed gameId=$gameId")
    }
    server.start()
}

private fun startRiskService() {
    val server = HttpServer.create(InetSocketAddress(8092), 0)
    server.executor = Executors.newCachedThreadPool()
    server.createContext("/api/risk/assessments") { exchange ->
        println("RISK  started   assessment")
        Thread.sleep(600)
        exchange.respond(200, """{"approved":true}""")
        println("RISK  completed assessment")
    }
    server.start()
}

private fun String?.parseQuery(): Map<String, String> =
    this
        ?.split('&')
        ?.mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2) {
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
            } else {
                null
            }
        }?.toMap()
        .orEmpty()

private fun HttpExchange.respond(
    status: Int,
    body: String,
) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
