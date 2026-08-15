package com.hkjc.training.betting

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class GameControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `known game returns the read-only mobile contract`() {
        mockMvc
            .perform(
                get("/api/v1/games/G-100")
                    .with(jwt().jwt { it.claim("scope", "games:read") }),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gameId").value("G-100"))
            .andExpect(jsonPath("$.homeTeam").value("Hong Kong Lions"))
            .andExpect(jsonPath("$.awayTeam").value("Falcons"))
            .andExpect(jsonPath("$.startTime").value("2026-08-04T12:00:00Z"))
            .andExpect(jsonPath("$.homeOdds").value(1.85))
            .andExpect(jsonPath("$.awayOdds").value(2.05))
    }

    @Test
    fun `unknown game returns the existing error contract`() {
        mockMvc
            .perform(
                get("/api/v1/games/G-404")
                    .with(jwt().jwt { it.claim("scope", "games:read") }),
            )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"))
    }

    @Test
    fun `bets write alone cannot read a game`() {
        mockMvc
            .perform(
                get("/api/v1/games/G-100")
                    .with(jwt().jwt { it.claim("scope", "bets:write") }),
            )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"))
            .andExpect(jsonPath("$.message").value("The required API scope is missing"))
    }

    @Test
    fun `configured Expo web origin receives CORS response`() {
        mockMvc
            .perform(
                options("/api/v1/games/G-100")
                    .header("Origin", "http://localhost:8081")
                    .header("Access-Control-Request-Method", "GET")
                    .header("Access-Control-Request-Headers", "Authorization"),
            ).andExpect(status().isOk)
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:8081"))
    }
}
