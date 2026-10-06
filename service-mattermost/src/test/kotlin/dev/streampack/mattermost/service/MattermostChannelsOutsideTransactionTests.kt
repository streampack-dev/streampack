/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/** Joining a channel with no transaction around it, as the bot does: its server loads with it. */
@SpringBootTest
class MattermostChannelsOutsideTransactionTests {
    @Autowired lateinit var mattermostService: MattermostService

    @Test
    fun `a new channel is joined, then joined again, and its autojoin set`() {
        val server = "mm-${UUID.randomUUID().toString().take(8)}"
        mattermostService.connect(server, "https://mattermost.example.com", "token-123")
        try {
            val channel = "abc123channelid00000000000"

            assertTrue(mattermostService.join(server, channel).contains("Joined"))
            assertTrue(mattermostService.join(server, channel).contains("Joined"))
            assertFalse(mattermostService.setAutojoin(server, channel, true).startsWith("Error"))
        } finally {
            // Outside a transaction nothing rolls back: other tests expect none configured.
            mattermostService.remove(server)
        }
    }
}
