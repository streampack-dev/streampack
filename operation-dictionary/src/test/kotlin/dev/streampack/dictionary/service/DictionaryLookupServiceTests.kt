/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.dictionary.dictionaryJson
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class DictionaryLookupServiceTests {

    @Autowired lateinit var lookupService: DictionaryLookupService

    private lateinit var httpServer: HttpServer
    private var baseUrl: String = ""

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        baseUrl = "http://localhost:${httpServer.address.port}"
    }

    @AfterEach
    fun tearDown() {
        httpServer.stop(0)
    }

    @Test
    fun `extracts word definition from JSON response`() {
        httpServer.createContext("/word") { exchange ->
            val json = dictionaryJson("ephemeral", "adjective", "lasting for a very short time.")
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val result = lookupService.lookupUrl("$baseUrl/word")
        assertNotNull(result)
        assertEquals("ephemeral", result!!.word)
        assertEquals("adjective", result.partOfSpeech)
        assertEquals("lasting for a very short time.", result.definition)
    }

    @Test
    fun `returns null for 404 response`() {
        httpServer.createContext("/missing") { exchange -> exchange.sendResponseHeaders(404, -1) }

        val result = lookupService.lookupUrl("$baseUrl/missing")
        assertNull(result)
    }

    @Test
    fun `returns null for unreachable server`() {
        val result = lookupService.lookupUrl("http://localhost:1/unreachable")
        assertNull(result)
    }

    @Test
    fun `returns null for malformed JSON`() {
        httpServer.createContext("/bad") { exchange ->
            val json = "not valid json"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val result = lookupService.lookupUrl("$baseUrl/bad")
        assertNull(result)
    }

    @Test
    fun `returns null for JSON missing required fields`() {
        httpServer.createContext("/incomplete") { exchange ->
            val json = """[{"word": "test"}]"""
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val result = lookupService.lookupUrl("$baseUrl/incomplete")
        assertNull(result)
    }

    @Test
    fun `returns null for empty array response`() {
        httpServer.createContext("/empty") { exchange ->
            val json = "[]"
            exchange.sendResponseHeaders(200, json.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }

        val result = lookupService.lookupUrl("$baseUrl/empty")
        assertNull(result)
    }
}
