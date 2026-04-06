/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.HtmlTitleFetcher
import dev.streampack.core.service.TitleFetcher
import dev.streampack.urltitle.service.TestTitleFetcher
import dev.streampack.urltitle.service.TestTitleFetcherConfiguration
import dev.streampack.urltitle.service.UrlTitleService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
@Import(TestTitleFetcherConfiguration::class)
class UrlTitleSummaryTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var titleFetcher: TitleFetcher
    @Autowired lateinit var operation: UrlTitleOperation
    @Autowired lateinit var service: UrlTitleService
    @Autowired lateinit var htmlTitleFetcher: HtmlTitleFetcher
    private val testFetcher: TestTitleFetcher
        get() = titleFetcher as TestTitleFetcher

    @BeforeEach
    fun setUp() {
        testFetcher.clear()
    }

    private fun message(text: String, nick: String = "testuser") =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .setHeader("nick", nick)
            .build()

    @Test
    fun `single URL produces title only`() {
        testFetcher.setTitle("https://kotlinlang.org/docs/coroutines", "Coroutines | Kotlin")
        val result = eventGateway.process(message("check https://kotlinlang.org/docs/coroutines"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertEquals("Coroutines | Kotlin", payload)
    }

    @Test
    @EnabledIfSystemProperty(named = "live.tests", matches = "true")
    fun `live fire youtube test`() {
        try {
            service.titleFetcher = htmlTitleFetcher
            val result =
                eventGateway.process(
                    message("check https://www.youtube.com/watch?v=jNDWnMfDnuw out")
                )
            assertInstanceOf(OperationResult.Success::class.java, result)
            val payload = (result as OperationResult.Success).payload as String
            assertTrue(payload.startsWith("YouTube:"), "Expected YouTube prefix in: $payload")
            assertTrue(payload.contains("Top 5 Hollywood"), "Expected title in: $payload")
            assertTrue(payload.contains("|"), "Expected channel separator in: $payload")
        } finally {
            service.titleFetcher = titleFetcher
        }
    }

    @Test
    fun `multiple distinct URLs produces anchored pairs with separator`() {
        testFetcher.setTitle("https://abc.example.com/page1", "Totally Unrelated Title")
        testFetcher.setTitle("https://xyz.example.com/page2", "Another Unrelated Title")
        val result =
            eventGateway.process(
                message("see https://abc.example.com/page1 and https://xyz.example.com/page2")
            )
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains(" || "), "Expected || separator in: $payload")
        assertTrue(
            payload.contains("""https://abc.example.com/page1 "Totally Unrelated Title""""),
            "Expected first URL-title pair in: $payload",
        )
        assertTrue(
            payload.contains("""https://xyz.example.com/page2 "Another Unrelated Title""""),
            "Expected second URL-title pair in: $payload",
        )
    }

    @Test
    fun `duplicate URLs are deduplicated to single title`() {
        testFetcher.setTitle("https://kotlinlang.org/docs/coroutines", "Coroutines | Kotlin")
        val result =
            eventGateway.process(
                message(
                    "https://kotlinlang.org/docs/coroutines is great https://kotlinlang.org/docs/coroutines"
                )
            )
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        // Deduplication produces one URL, so single-URL format (title only)
        assertEquals("Coroutines | Kotlin", payload)
    }

    @Test
    fun `output does not include nick or mentioned prefix`() {
        testFetcher.setTitle("https://kotlinlang.org/docs/coroutines", "Coroutines | Kotlin")
        val result =
            eventGateway.process(message("https://kotlinlang.org/docs/coroutines", nick = "alice"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(!payload.contains("alice"), "Nick should not appear in: $payload")
        assertTrue(!payload.contains("mentioned"), "mentioned should not appear in: $payload")
    }

    @Test
    fun `URLs with no fetchable title are excluded`() {
        testFetcher.setTitle("https://abc.example.com/page1", "Totally Unrelated Title")
        // second URL has no title registered in the test fetcher
        val result =
            eventGateway.process(
                message("https://abc.example.com/page1 and https://nope.example.com/missing")
            )
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        // Only one title resolves, so single-URL format (title only)
        assertEquals("Totally Unrelated Title", payload)
    }

    @Test
    fun `invalid tls certificate returns derived title warning`() {
        val url = "https://badcert.example.com/deeply-interesting-article"
        testFetcher.setInvalidCertificate(url)

        val result = eventGateway.process(message("read this $url"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(
            payload.contains("Deeply Interesting Article"),
            "Expected URL-derived fallback title",
        )
        assertTrue(payload.contains("TLS certificate invalid"), "Expected TLS warning in payload")
    }
}
