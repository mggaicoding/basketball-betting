package com.hkjc.training.betting

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class SecurityApiTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `no token returns 401`() {
        mockMvc
            .perform(placeBetRequest())
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
    }

    @Test
    fun `valid token without bets write returns 403`() {
        mockMvc
            .perform(placeBetRequest().with(jwt().jwt { it.claim("scope", "bets:read") }))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"))
    }

    @Test
    fun `valid token with bets write returns 201`() {
        mockMvc
            .performSuspending(placeBetRequest().with(jwt().jwt { it.claim("scope", "bets:write") }))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("ACCEPTED"))
    }

    private fun placeBetRequest() =
        post("/api/v1/bets")
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {
                  "gameId": "G-100",
                  "selection": "HOME",
                  "stake": 100
                }
                """.trimIndent(),
            )
}
