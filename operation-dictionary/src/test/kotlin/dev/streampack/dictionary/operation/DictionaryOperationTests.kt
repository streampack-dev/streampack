/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.PageFetcher
import dev.streampack.dictionary.dictionaryJson
import dev.streampack.dictionary.service.DictionaryLookupService
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
class DictionaryOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var testLookupService: LocalDictionaryLookupService

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
        fun localDictionaryLookupService(pageFetcher: PageFetcher): LocalDictionaryLookupService =
            LocalDictionaryLookupService(pageFetcher)
    }

    @Test
    fun `define lookup returns definition`() {
        httpServer.createContext("/api/v2/entries/en/ephemeral") { exchange ->
            val json = dictionaryJson("ephemeral", "adjective", "lasting for a very short time.")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val result = eventGateway.process(message("define ephemeral"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("ephemeral"))
        assertTrue(payload.contains("adjective"))
        assertTrue(payload.contains("lasting for a very short time."))
    }

    @Test
    fun `define lookup is case insensitive`() {
        httpServer.createContext("/api/v2/entries/en/ephemeral") { exchange ->
            val json = dictionaryJson("ephemeral", "adjective", "lasting for a very short time.")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val result = eventGateway.process(message("Define EPHEMERAL"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `unknown word returns not handled`() {
        httpServer.createContext("/api/v2/entries/en/xyzzyplugh") { exchange ->
            exchange.sendResponseHeaders(404, -1)
        }

        val result = eventGateway.process(message("define xyzzyplugh"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `non-matching input is not handled`() {
        val result = eventGateway.process(message("defin ephemeral"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `plain text is not handled`() {
        val result = eventGateway.process(message("hello world"))
        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `triggered define lookup is handled`() {
        httpServer.createContext("/api/v2/entries/en/ephemeral") { exchange ->
            val json = dictionaryJson("ephemeral", "adjective", "lasting for a very short time.")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val result = eventGateway.process(message("!define ephemeral"))
        assertInstanceOf(OperationResult.Success::class.java, result)
    }
}

/** DictionaryLookupService that rewrites URLs to point at a local HTTP server */
class LocalDictionaryLookupService(pageFetcher: PageFetcher) :
    DictionaryLookupService(pageFetcher) {
    var baseUrl: String = ""

    override fun buildUrl(word: String): String {
        if (baseUrl.isBlank()) return super.buildUrl(word)
        return "$baseUrl/api/v2/entries/en/$word"
    }
}
