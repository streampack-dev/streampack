/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.core.entity.ChannelControlOptions
import dev.streampack.core.entity.MessageLog
import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.core.repository.MessageLogRepository
import dev.streampack.core.service.ThrottleService
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import org.hamcrest.Matchers.contains
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** With a configured log zone, days are split at its midnight (#212) */
@SpringBootTest(properties = ["streampack.logs.zone=America/New_York"])
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class LogDayZoneTests {
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var optionsRepository: ChannelControlOptionsRepository
    @Autowired lateinit var messageLogRepository: MessageLogRepository
    @Autowired lateinit var throttleService: ThrottleService

    private val prov = "irc://libera/%23zoned"

    @BeforeEach
    fun setUp() {
        throttleService.clear()
        optionsRepository.save(
            ChannelControlOptions(
                provenanceUri = prov,
                visible = true,
                logged = true,
                active = true,
            ),
        )
        line("late on the thirteenth", "2026-10-14T03:00:00Z")
        line("early on the fourteenth", "2026-10-14T05:00:00Z")
    }

    private fun line(content: String, at: String) =
        messageLogRepository.save(
            MessageLog(
                provenanceUri = prov,
                sender = "alice",
                content = content,
                timestamp = Instant.parse(at),
            ),
        )

    @Test
    fun `a day holds the lines from its local midnight to the next`() {
        mockMvc.get("/logs?provenance=$prov&day=2026-10-13").andExpect {
            status { isOk() }
            jsonPath("$.entries[*].content") { value(contains("late on the thirteenth")) }
        }
        mockMvc.get("/logs?provenance=$prov&day=2026-10-14").andExpect {
            status { isOk() }
            jsonPath("$.entries[*].content") { value(contains("early on the fourteenth")) }
        }
    }

    @Test
    fun `search hits link to their day in the configured zone`() {
        mockMvc
            .get("/logs/search") {
                param("provenance", prov)
                param("q", "the thirteenth")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.hits[0].day") { value("2026-10-13") }
            }
        mockMvc
            .get("/logs/search") {
                param("provenance", prov)
                param("q", "the fourteenth")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.hits[0].day") { value("2026-10-14") }
            }
    }
}
