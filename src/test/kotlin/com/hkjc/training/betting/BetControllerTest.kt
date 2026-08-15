package com.hkjc.training.betting

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Every POST /api/v1/bets scenario that the controller itself decides: accepted bets,
 * domain failures, and the stable error contract.
 *
 * Authentication and authorization are deliberately absent. The filter chain rejects those
 * requests before this controller is reached, so they belong to [SecurityApiTest].
 */
@SpringBootTest
@AutoConfigureMockMvc
class BetControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `HOME returns 201 with fixed odds and ACCEPTED status`() {
        mockMvc
            .performSuspending(placeBet(selection = "HOME"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.odds").value(1.85))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
    }

    @Test
    fun `AWAY returns 201 with fixed odds and ACCEPTED status`() {
        mockMvc
            .performSuspending(placeBet(selection = "AWAY"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.odds").value(2.05))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
    }

    @Test
    fun `unknown game returns 404 with GAME_NOT_FOUND code`() {
        mockMvc
            .performSuspending(placeBet(gameId = "G-404"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"))
    }

    @Test
    fun `unknown game returns every field of the stable error contract`() {
        mockMvc
            .performSuspending(placeBet(gameId = "G-404", traceId = "demo-trace-404"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("GAME_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Game G-404 was not found"))
            .andExpect(jsonPath("$.path").value("/api/v1/bets"))
            .andExpect(jsonPath("$.traceId").value("demo-trace-404"))
            .andExpect(jsonPath("$.timestamp").exists())
    }

    private fun placeBet(
        gameId: String = "G-100",
        selection: String = "HOME",
        stake: String = "100",
        traceId: String? = null,
    ): MockHttpServletRequestBuilder =
        post("/api/v1/bets")
            .with(jwt().jwt { it.claim("scope", "bets:write") })
            .contentType(MediaType.APPLICATION_JSON)
            .apply { traceId?.let { header("X-Trace-Id", it) } }
            .content(
                """
                {
                  "gameId": "$gameId",
                  "selection": "$selection",
                  "stake": $stake
                }
                """.trimIndent(),
            )
}
