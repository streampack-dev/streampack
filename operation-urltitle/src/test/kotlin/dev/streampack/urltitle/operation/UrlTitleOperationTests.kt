/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.urltitle.service.UrlTitleService
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class UrlTitleOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var urlTitleService: UrlTitleService

    private fun principal(role: Role) =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = role.name.lowercase(),
            displayName = role.name,
            role = role,
        )

    private fun provenance(protocol: Protocol = Protocol.CONSOLE, user: UserPrincipal? = null) =
        Provenance(protocol = protocol, serviceId = "", replyTo = "local", user = user)

    private fun textMessage(
        text: String,
        protocol: Protocol = Protocol.CONSOLE,
        user: UserPrincipal? = null,
    ) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance(protocol, user))
            .build()

    private fun adminMessage(text: String) = textMessage(text, user = principal(Role.ADMIN))

    private fun payloadOf(result: Any?): String {
        assertInstanceOf(OperationResult.Success::class.java, result)
        return (result as OperationResult.Success).payload as String
    }

    @Test
    fun `messages from HTTP protocol are not handled by url title`() {
        val result = eventGateway.process(textMessage("https://example.com", Protocol.HTTP))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `messages with no URLs are not handled`() {
        val result = eventGateway.process(textMessage("just a regular message"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `messages from IRC protocol with URLs are eligible`() {
        val operation =
            UrlTitleOperation(urlTitleService, dev.streampack.urltitle.config.UrlTitleProperties())
        val message = textMessage("check out https://example.com", Protocol.IRC)
        assertTrue(operation.canHandle(message))
    }

    @Test
    fun `messages from MAILTO protocol are not eligible`() {
        val operation =
            UrlTitleOperation(urlTitleService, dev.streampack.urltitle.config.UrlTitleProperties())
        val message = textMessage("check out https://example.com", Protocol.MAILTO)
        assertTrue(!operation.canHandle(message))
    }

    @Test
    fun `url ignore list returns ignored hosts for anyone`() {
        assertTrue(
            payloadOf(eventGateway.process(textMessage("url ignore list")))
                .contains("Ignored hosts include:")
        )
        assertTrue(
            payloadOf(
                    eventGateway.process(
                        textMessage("url ignore list", user = principal(Role.USER))
                    )
                )
                .contains("Ignored hosts include:")
        )
    }

    @Test
    fun `url ignore add succeeds for admin`() {
        val payload =
            payloadOf(eventGateway.process(adminMessage("url ignore add test-domain.example.com")))
        assertTrue(payload.contains("Added test-domain.example.com"))
    }

    @Test
    fun `url ignore add reports the normalized entry`() {
        val payload =
            payloadOf(
                eventGateway.process(
                    adminMessage("url ignore add https://www.Repopack.example.com/Project/")
                )
            )
        assertEquals("Added repopack.example.com/project to ignored hosts.", payload)
        assertTrue(urlTitleService.findAllIgnoredHosts().contains("repopack.example.com/project"))
    }

    @Test
    fun `url ignore add rejects a malformed entry`() {
        val result = eventGateway.process(adminMessage("url ignore add not_a_host!"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `url ignore delete succeeds for admin`() {
        urlTitleService.addIgnoredHost("removable.example.com")
        val payload =
            payloadOf(eventGateway.process(adminMessage("url ignore delete removable.example.com")))
        assertTrue(payload.contains("Removed removable.example.com"))
    }

    @Test
    fun `url ignore add is refused for non-admins`() {
        for (user in listOf(null, principal(Role.USER))) {
            val result =
                eventGateway.process(textMessage("url ignore add refused.example.com", user = user))
            assertInstanceOf(OperationResult.Error::class.java, result)
        }
        assertFalse(urlTitleService.findAllIgnoredHosts().contains("refused.example.com"))
    }

    @Test
    fun `url ignore delete is refused for non-admins`() {
        val result =
            eventGateway.process(
                textMessage("url ignore delete pastebin.com", user = principal(Role.USER))
            )
        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue(urlTitleService.isIgnoredHost("https://pastebin.com/abc"))
    }
}
