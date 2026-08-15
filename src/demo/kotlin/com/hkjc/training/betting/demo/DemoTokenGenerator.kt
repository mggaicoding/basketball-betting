package com.hkjc.training.betting.demo

import com.hkjc.training.betting.configuration.DEMO_SECRET
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.time.Instant
import java.util.Date

fun main() {
    println("VALID_NO_SCOPE_TOKEN=${token(scope = "")}")
    println("VALID_GAMES_READ_TOKEN=${token(scope = "games:read")}")
    println("VALID_BETS_WRITE_TOKEN=${token(scope = "bets:write")}")
    println("VALID_MOBILE_TOKEN=${token(scope = "games:read bets:write")}")
}

private fun token(scope: String): String {
    val issuedAt = Instant.now()
    val claims =
        JWTClaimsSet
            .Builder()
            .issuer("hkjc-training-local")
            .subject("module-1-helper")
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plusSeconds(24 * 60 * 60)))
            .claim("scope", scope)
            .build()

    return SignedJWT(JWSHeader(JWSAlgorithm.HS256), claims)
        .apply { sign(MACSigner(DEMO_SECRET)) }
        .serialize()
}
