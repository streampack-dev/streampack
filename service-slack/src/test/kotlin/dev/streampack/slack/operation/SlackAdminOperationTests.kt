/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.operation

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
class SlackAdminOperationTests {

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

    private fun slackMessage(text: String, user: UserPrincipal = superAdmin) =
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
    fun `slack connect with SUPER_ADMIN returns success`() {
        val result =
            eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Connecting"))
    }

    @Test
    fun `slack connect without SUPER_ADMIN returns error`() {
        val result =
            eventGateway.process(
                slackMessage("slack connect jvm-news xoxb-test xapp-test", regularUser)
            )
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("SUPER_ADMIN"))
    }

    @Test
    fun `slack status returns success`() {
        val result = eventGateway.process(slackMessage("slack status"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `slack join after connect returns success`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        val result = eventGateway.process(slackMessage("slack join jvm-news #java"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Joined"))
    }

    @Test
    fun `slack autojoin updates flag`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        eventGateway.process(slackMessage("slack join jvm-news #java"))
        val result = eventGateway.process(slackMessage("slack autojoin jvm-news #java true"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("true"))
    }

    @Test
    fun `slack join nonexistent workspace returns error`() {
        val result = eventGateway.process(slackMessage("slack join nonexistent #java"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `bare slack returns help text`() {
        val result = eventGateway.process(slackMessage("slack"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue(
            (result as OperationResult.Success).payload.toString().contains("Slack Admin Commands")
        )
    }

    @Test
    fun `slack unknown subcommand returns error`() {
        val result = eventGateway.process(slackMessage("slack frobnicate"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.contains("Unknown"))
    }

    @Test
    fun `non-slack message is not handled`() {
        val result = eventGateway.process(slackMessage("karma foo++"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `slack autoconnect updates flag`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        val result = eventGateway.process(slackMessage("slack autoconnect jvm-news true"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("true"))
    }

    @Test
    fun `slack mute returns success for valid channel`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        eventGateway.process(slackMessage("slack join jvm-news #java"))
        val result = eventGateway.process(slackMessage("slack mute jvm-news #java"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Muted"))
    }

    @Test
    fun `slack unmute returns success for valid channel`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        eventGateway.process(slackMessage("slack join jvm-news #java"))
        val result = eventGateway.process(slackMessage("slack unmute jvm-news #java"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Unmuted"))
    }

    @Test
    fun `slack disconnect returns success for known workspace`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        val result = eventGateway.process(slackMessage("slack disconnect jvm-news"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `slack signal sets per-workspace signal character`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        val result = eventGateway.process(slackMessage("slack signal jvm-news ~"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("~"))
    }

    @Test
    fun `slack signal without character resets to default`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        eventGateway.process(slackMessage("slack signal jvm-news ~"))
        val result = eventGateway.process(slackMessage("slack signal jvm-news"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("reset"))
    }

    @Test
    fun `slack remove returns success for known workspace`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        val result = eventGateway.process(slackMessage("slack remove jvm-news"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("removed"))
    }

    @Test
    fun `slack remove allows reconnect with same name`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        eventGateway.process(slackMessage("slack remove jvm-news"))
        val result = eventGateway.process(slackMessage("slack connect jvm-news xoxb-new xapp-new"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Connecting"))
    }

    @Test
    fun `slack remove nonexistent workspace returns error`() {
        val result = eventGateway.process(slackMessage("slack remove nonexistent"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `slack connect with new credentials updates existing workspace`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        val result =
            eventGateway.process(slackMessage("slack connect jvm-news xoxb-other xapp-other"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Connecting"))
    }

    @Test
    fun `slack connect by name reconnects existing workspace`() {
        eventGateway.process(slackMessage("slack connect jvm-news xoxb-test xapp-test"))
        eventGateway.process(slackMessage("slack disconnect jvm-news"))
        val result = eventGateway.process(slackMessage("slack connect jvm-news"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        assertTrue((result as OperationResult.Success).payload.toString().contains("Connecting"))
    }

    @Test
    fun `slack connect by name for unknown workspace returns error`() {
        val result = eventGateway.process(slackMessage("slack connect nonexistent"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }
}
