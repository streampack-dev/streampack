/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Joining a channel with no transaction around it, as the bot does: its workspace loads with it.
 */
@SpringBootTest
class SlackChannelsOutsideTransactionTests {
    @Autowired lateinit var slackService: SlackService

    @Test
    fun `a new channel is joined, then joined again, and its autojoin set`() {
        val workspace = "ws-${UUID.randomUUID().toString().take(8)}"
        slackService.connect(workspace, "xoxb-test", "xapp-test")
        try {
            assertTrue(slackService.join(workspace, "C0JAVA0001").startsWith("Registered"))
            assertTrue(slackService.join(workspace, "C0JAVA0001").startsWith("Registered"))
            assertFalse(slackService.setAutojoin(workspace, "C0JAVA0001", true).startsWith("Error"))
        } finally {
            // Outside a transaction nothing rolls back: other tests expect none configured.
            slackService.remove(workspace)
        }
    }
}
