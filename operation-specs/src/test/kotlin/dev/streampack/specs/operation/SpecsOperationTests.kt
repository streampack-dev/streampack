/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.PageFetcher
import dev.streampack.specs.model.SpecRequest
import dev.streampack.specs.model.SpecType
import dev.streampack.specs.service.SpecLookupService
import dev.streampack.specs.service.SpecLookupServiceTests.Companion.jepHtml
import dev.streampack.specs.service.SpecLookupServiceTests.Companion.jsrHtml
import dev.streampack.specs.service.SpecLookupServiceTests.Companion.rfcHtml
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class SpecsOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var testLookupService: LocalSpecLookupService

    private lateinit var httpServer: HttpServer
    private var baseUrl: String = ""

    private fun provenance() =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")

    private fun message(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, provenance()).build()

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        baseUrl = "http://localhost:${httpServer.address.port}"
        testLookupService.baseUrl = baseUrl
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
    }

    /** Overrides URL resolution to point at the local HTTP server */
    @TestConfiguration
    class Config {
        @Bean
        @Primary
        fun localSpecLookupService(pageFetcher: PageFetcher): LocalSpecLookupService =
            LocalSpecLookupService(pageFetcher)
    }

    @Test
    fun `rfc lookup returns title and URL`() {
        httpServer.createContext("/rfc/rfc2812.html") { exchange ->
            val html = rfcHtml(2812, "Internet Relay Chat: Client Protocol")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val result = eventGateway.process(message("rfc 2812"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Internet Relay Chat: Client Protocol"))
        assertTrue(payload.contains("rfc 2812:"))
    }

    @Test
    fun `jep lookup returns title and URL`() {
        httpServer.createContext("/jeps/3") { exchange ->
            val html = jepHtml(3, "JDK Release Process")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val result = eventGateway.process(message("jep 3"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("JDK Release Process"))
    }

    @Test
    fun `jsr lookup returns title and URL`() {
        httpServer.createContext("/en/jsr/detail") { exchange ->
            val html = jsrHtml(3, "Java Management Extensions (JMX) Specification")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val result = eventGateway.process(message("jsr 3"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("Java Management Extensions"))
    }

    @Test
    fun `spec lookup is case insensitive`() {
        httpServer.createContext("/rfc/rfc2812.html") { exchange ->
            val html = rfcHtml(2812, "Internet Relay Chat: Client Protocol")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val result = eventGateway.process(message("RFC 2812"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `spec lookup without space between type and number works`() {
        httpServer.createContext("/rfc/rfc2812.html") { exchange ->
            val html = rfcHtml(2812, "Internet Relay Chat: Client Protocol")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val result = eventGateway.process(message("rfc2812"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `non-existent spec returns not handled`() {
        httpServer.createContext("/rfc/rfc999999.html") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }

        val result = eventGateway.process(message("rfc 999999"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `non-spec text is not handled`() {
        val result = eventGateway.process(message("hello world"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `spec type with zero identifier is not handled`() {
        val result = eventGateway.process(message("rfc 0"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `triggered spec lookup is handled`() {
        httpServer.createContext("/rfc/rfc2812.html") { exchange ->
            val html = rfcHtml(2812, "Internet Relay Chat: Client Protocol")
            exchange.sendResponseHeaders(200, html.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(html.toByteArray()) }
        }

        val result = eventGateway.process(message("!rfc 2812"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }
}

/** SpecLookupService that rewrites URLs to point at a local HTTP server */
class LocalSpecLookupService(pageFetcher: PageFetcher) : SpecLookupService(pageFetcher) {
    var baseUrl: String = ""

    override fun lookup(request: SpecRequest): String? {
        if (baseUrl.isBlank()) return super.lookup(request)
        val path =
            when (request.type) {
                SpecType.RFC -> "/rfc/rfc${request.identifier}.html"
                SpecType.JEP -> "/jeps/${request.identifier}"
                SpecType.JSR -> "/en/jsr/detail?id=${request.identifier}"
                SpecType.PEP -> "/pep-${"%04d".format(request.identifier)}/"
            }
        return lookupUrl("$baseUrl$path", request.type)
    }
}
