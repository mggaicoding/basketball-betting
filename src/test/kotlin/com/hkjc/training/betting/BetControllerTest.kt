package com.hkjc.training.betting

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/** The vertical slice: a request reaches the controller, the service, and the repository. */
@SpringBootTest
@AutoConfigureMockMvc
class BetControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `HOME returns 201 with the home odds and ACCEPTED status`() {
        mockMvc
            .performSuspending(placeBet(selection = "HOME"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.betId").exists())
            .andExpect(jsonPath("$.odds").value(1.85))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
    }

    @Test
    fun `AWAY returns 201 with the away odds and ACCEPTED status`() {
        mockMvc
            .performSuspending(placeBet(selection = "AWAY"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.odds").value(2.05))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
    }

    @Test
    fun `the response echoes the trace identifier the caller supplied`() {
        mockMvc
            .performSuspending(placeBet(traceId = "demo-1-trace"))
            .andExpect(status().isCreated)
            .andExpect(header().string("X-Trace-Id", "demo-1-trace"))
    }

    private fun placeBet(
        gameId: String = "G-100",
        selection: String = "HOME",
        stake: String = "100",
        traceId: String? = null,
    ): MockHttpServletRequestBuilder =
        post("/api/v1/bets")
            .header("X-Customer-Id", "C-100")
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
