/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.OperationConfigService
import dev.streampack.core.service.TransformerChainService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class)
class EgressTransformationTests {

    @Autowired lateinit var transformerChain: TransformerChainService
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var configService: OperationConfigService
    @Autowired lateinit var contentFilter: ContentFilterTransformer

    private val provenance =
        Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#test")

    @BeforeEach
    fun setup() {
        configService.clearCache()
    }

    // -- Transformer chain tests --

    @Test
    fun `chain - content filter applies to Success results`() {
        configService.setConfigValue(provenance.encode(), "content-filter", "hello", "goodbye")

        val result = OperationResult.Success("hello world")
        val transformed = transformerChain.apply(result, provenance)

        assertInstanceOf(OperationResult.Success::class.java, transformed)
        assertEquals("goodbye world", (transformed as OperationResult.Success).payload)
    }

    @Test
    fun `chain - disabled content-filter is skipped`() {
        configService.setEnabled(provenance.encode(), "content-filter", false)
        configService.setConfigValue(provenance.encode(), "content-filter", "hello", "goodbye")

        val result = OperationResult.Success("hello world")
        val transformed = transformerChain.apply(result, provenance)

        assertInstanceOf(OperationResult.Success::class.java, transformed)
        assertEquals("hello world", (transformed as OperationResult.Success).payload)
    }

    @Test
    fun `chain - non-Success results pass through unchanged`() {
        val error = OperationResult.Error("something failed")
        val transformed = transformerChain.apply(error, provenance)

        assertEquals(error, transformed)
    }

    @Test
    fun `chain - NotHandled passes through unchanged`() {
        val notHandled = OperationResult.NotHandled
        val transformed = transformerChain.apply(notHandled, provenance)

        assertEquals(notHandled, transformed)
    }

    @Test
    fun `chain - end-to-end transformation via EventGateway`() {
        configService.setConfigValue(provenance.encode(), "content-filter", "provenance", "target")

        val message =
            MessageBuilder.withPayload("channel").setHeader(Provenance.HEADER, provenance).build()
        val result = eventGateway.process(message)

        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        // ChannelConfigOperation returns help text containing "provenance"
        assertTrue(payload.contains("target"))
        assertFalse(payload.lowercase().contains("provenance"))
    }

    // -- Content filter tests --

    @Test
    fun `filter - replaces configured words case-insensitively`() {
        configService.setConfigValue(provenance.encode(), "content-filter", "badword", "goodword")

        val result = OperationResult.Success("This is a Badword in a sentence")
        val transformed = contentFilter.transform(result, provenance)

        assertInstanceOf(OperationResult.Success::class.java, transformed)
        assertEquals(
            "This is a goodword in a sentence",
            (transformed as OperationResult.Success).payload,
        )
    }

    @Test
    fun `filter - multiple replacements apply`() {
        val uri = provenance.encode()
        configService.setConfigValue(uri, "content-filter", "foo", "bar")
        configService.setConfigValue(uri, "content-filter", "baz", "qux")

        val result = OperationResult.Success("foo and baz together")
        val transformed = contentFilter.transform(result, provenance)

        assertEquals("bar and qux together", (transformed as OperationResult.Success).payload)
    }

    @Test
    fun `filter - empty config map is passthrough`() {
        val result = OperationResult.Success("unchanged text")
        val transformed = contentFilter.transform(result, provenance)

        assertEquals(result, transformed)
    }

    @Test
    fun `filter - skips bridged messages`() {
        val bridgedProvenance = provenance.copy(metadata = mapOf(Provenance.BRIDGED to true))

        val canTransform =
            contentFilter.canTransform(OperationResult.Success("test"), bridgedProvenance)
        assertFalse(canTransform)
    }

    @Test
    fun `filter - skips non-Success results`() {
        val canTransform = contentFilter.canTransform(OperationResult.Error("error"), provenance)
        assertFalse(canTransform)
    }

    @Test
    fun `filter - disabled content-filter does not transform via EventGateway`() {
        configService.setEnabled(provenance.encode(), "content-filter", false)
        configService.setConfigValue(provenance.encode(), "content-filter", "provenance", "target")

        val message =
            MessageBuilder.withPayload("channel").setHeader(Provenance.HEADER, provenance).build()
        val result = eventGateway.process(message)

        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        // Content filter disabled, so "provenance" remains in the help text
        assertTrue(payload.contains("provenance"))
        assertFalse(payload.contains("target"))
    }
}
