/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

/**
 * Joining a channel as the bot does it, with no transaction around it: a channel's address comes
 * from its network's name, which must be loaded with it. (`irc join` registered a new channel, then
 * failed with a LazyInitializationException reading the network.)
 */
@SpringBootTest
class IrcChannelsOutsideTransactionTests {
    @Autowired lateinit var ircService: IrcService

    @Test
    fun `a new channel is joined, then joined again, and its autojoin set`() {
        val network = "net-${UUID.randomUUID().toString().take(8)}"
        ircService.connect(network, "irc.example.net", "nevet", null, null)
        try {
            assertTrue(ircService.join(network, "#repopack").startsWith("Joined"))
            assertTrue(ircService.join(network, "#repopack").startsWith("Joined"))
            assertFalse(ircService.setAutojoin(network, "#repopack", true).startsWith("Error"))
        } finally {
            // Outside a transaction nothing rolls back: other tests expect none configured.
            ircService.remove(network)
        }
    }
}
