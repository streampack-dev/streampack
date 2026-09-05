/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class MattermostAdminOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private val superAdmin =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.SUPER_ADMIN,
        )

    private val regularUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "user",
            displayName = "User",
            role = Role.USER,
        )

    private fun consoleMessage(text: String, user: UserPrincipal = superAdmin) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.CONSOLE,
                    serviceId = "",
                    replyTo = "local",
                    user = user,
                ),
            )
            .build()

    @Test
    fun `mattermost connect with SUPER_ADMIN returns success`() {
        val result =
            eventGateway.process(
                consoleMessage("mattermost connect work https://mattermost.example.com token-123")
            )
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `mattermost connect without SUPER_ADMIN returns error`() {
        val result =
            eventGateway.process(
                consoleMessage(
                    "mattermost connect work https://mattermost.example.com token-123",
                    regularUser,
                )
            )
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `mattermost join after connect returns success`() {
        eventGateway.process(
            consoleMessage("mattermost connect work https://mattermost.example.com token-123")
        )
        val result =
            eventGateway.process(consoleMessage("mattermost join work abc123channelid00000000000"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Joined"))
    }

    @Test
    fun `bare mattermost returns help text`() {
        val result = eventGateway.process(consoleMessage("mattermost"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(
            (result as OperationResult.Success)
                .payload
                .toString()
                .contains("Mattermost Admin Commands")
        )
    }

    @Test
    fun `mattermost unknown subcommand returns error`() {
        val result = eventGateway.process(consoleMessage("mattermost frobnicate"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }
}
