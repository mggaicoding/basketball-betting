package com.hkjc.training.betting

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
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
            .perform(get("/api/v1/games/G-100"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.gameId").value("G-100"))
            .andExpect(jsonPath("$.homeTeam").value("Hong Kong Lions"))
            .andExpect(jsonPath("$.awayTeam").value("Falcons"))
            .andExpect(jsonPath("$.startTime").value("2026-08-04T12:00:00Z"))
            .andExpect(jsonPath("$.homeOdds").value(1.85))
            .andExpect(jsonPath("$.awayOdds").value(2.05))
    }
}
