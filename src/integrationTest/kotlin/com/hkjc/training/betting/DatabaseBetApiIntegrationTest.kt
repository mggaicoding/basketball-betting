package com.hkjc.training.betting

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.request
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlin.test.assertEquals

@SpringBootTest(
    properties = [
        // Testcontainers reports the container started once PostgreSQL logs that it is ready,
        // which happens inside the container. On Docker runtimes that forward ports through a
        // VM -- Colima and Rancher Desktop among them -- the host-side forward is established a
        // moment later, so the first connection from the test JVM can still be refused.
        // Retrying the initial connection covers that window.
        "spring.flyway.connect-retries=10",
        "spring.flyway.connect-retries-interval=1s",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("database", "test")
@Testcontainers
class DatabaseBetApiIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbcClient: JdbcClient

    @Test
    fun `Flyway schema and JDBC adapter persist an accepted bet`() {
        mockMvc
            .perform(
                get("/api/v1/games/G-100"),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.homeTeam").value("Hong Kong Lions"))
            .andExpect(jsonPath("$.startTime").value("2026-08-04T12:00:00Z"))

        val before = jdbcClient.sql("select count(*) from bet").query(Int::class.java).single()

        val initialResult =
            mockMvc
                .perform(
                    post("/api/v1/bets")
                        .header("X-Customer-Id", "C-100")
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
                ).andExpect(request().asyncStarted())
                .andReturn()

        mockMvc.perform(asyncDispatch(initialResult)).andExpect(status().isCreated)

        val after = jdbcClient.sql("select count(*) from bet").query(Int::class.java).single()
        assertEquals(before + 1, after)
    }
}
