/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.service.ChannelControlService
import dev.streampack.core.service.UserResolutionService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

/** `slack status` names the bot user the token belongs to (#242). */
class SlackStatusTests {
    private val adapter =
        SlackAdapter(
            workspaceName = "work",
            botToken = "xoxb-test",
            appToken = "xapp-test",
            initialSignalCharacter = "!",
            eventGateway = Mockito.mock(EventGateway::class.java),
            userResolutionService = Mockito.mock(UserResolutionService::class.java),
            channelControlService = Mockito.mock(ChannelControlService::class.java),
        )

    @Test
    fun `status names the bot user once auth test has answered`() {
        assertEquals("connected", adapter.status())
        adapter.identified("U0BOT", "lantern")
        assertEquals("connected as lantern", adapter.status())
    }
}
