package com.hkjc.training.betting

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

/**
 * GET /api/v1/bets/{betId}. The last two cases are the ones a generated suite leaves out:
 * they come from the requirement, not from the code.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BetQueryApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `the owner reads their own bet`() {
        val betId = placeBet(customer = "C-100")

        mockMvc
            .perform(get("/api/v1/bets/$betId").with(readToken("C-100")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.betId").value(betId))
            .andExpect(jsonPath("$.gameId").value("G-100"))
            .andExpect(jsonPath("$.selection").value("HOME"))
            .andExpect(jsonPath("$.stake").value(100))
            .andExpect(jsonPath("$.odds").value(1.85))
            .andExpect(jsonPath("$.potentialPayout").value(185.00))
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.placedAt").exists())
    }

    @Test
    fun `an unknown bet returns 404 with a stable code`() {
        mockMvc
            .perform(get("/api/v1/bets/B-404").with(readToken("C-100")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("BET_NOT_FOUND"))
            .andExpect(jsonPath("$.path").value("/api/v1/bets/B-404"))
    }

    @Test
    fun `no token is rejected before the controller is reached`() {
        mockMvc
            .perform(get("/api/v1/bets/B-1"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `a token without bets read is forbidden`() {
        mockMvc
            .perform(get("/api/v1/bets/B-1").with(jwt().jwt { it.claim("scope", "bets:write") }))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `another customer's bet is reported as not found, not as forbidden`() {
        val betId = placeBet(customer = "C-100")

        mockMvc
            .perform(get("/api/v1/bets/$betId").with(readToken("C-999")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("BET_NOT_FOUND"))
    }

    @Test
    fun `the response never carries the customer identifier`() {
        val betId = placeBet(customer = "C-100")

        mockMvc
            .perform(get("/api/v1/bets/$betId").with(readToken("C-100")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.customerId").doesNotExist())
    }

    private fun readToken(customer: String) =
        jwt().jwt { it.subject(customer).claim("scope", "bets:read") }

    private fun placeBet(customer: String): String {
        val body =
            mockMvc
                .performSuspending(
                    post("/api/v1/bets")
                        .with(jwt().jwt { it.subject(customer).claim("scope", "bets:write") })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "gameId": "G-100",
                              "selection": "HOME",
                              "stake": 100
                            }
                            """.trimIndent(),
                        ),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return JsonMapper.builder().build().readTree(body).get("betId").asString()
    }
}
