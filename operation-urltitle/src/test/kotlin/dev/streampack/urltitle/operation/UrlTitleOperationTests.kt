/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.urltitle.service.UrlTitleService
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

    private fun provenance(protocol: Protocol = Protocol.CONSOLE) =
        Provenance(protocol = protocol, serviceId = "", replyTo = "local")

    private fun textMessage(text: String, protocol: Protocol = Protocol.CONSOLE) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, provenance(protocol)).build()

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
    fun `url ignore list returns ignored hosts`() {
        val result = eventGateway.process(textMessage("url ignore list"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Ignored hosts include:"))
    }

    @Test
    fun `url ignore add succeeds`() {
        val result = eventGateway.process(textMessage("url ignore add test-domain.example.com"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Added test-domain.example.com"))
    }

    @Test
    fun `url ignore delete succeeds`() {
        urlTitleService.addIgnoredHost("removable.example.com")
        val result = eventGateway.process(textMessage("url ignore delete removable.example.com"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Removed removable.example.com"))
    }
}
