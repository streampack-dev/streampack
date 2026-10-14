/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.dictionary.StubServer
import dev.streampack.dictionary.dictionaryJson
import dev.streampack.dictionary.freeDictionaryJson
import dev.streampack.dictionary.service.DictionaryLookupService
import dev.streampack.test.ResetDatabaseBeforeEach
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

@SpringBootTest
@ResetDatabaseBeforeEach
class DictionaryOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var lookupService: DictionaryLookupService

    companion object {
        private val stub = StubServer()

        @JvmStatic
        @DynamicPropertySource
        fun dictionaries(registry: DynamicPropertyRegistry) {
            registry.add("streampack.dictionary.free-dictionary-url") { "${stub.url}/free" }
            registry.add("streampack.dictionary.dictionaryapi-dev-url") { "${stub.url}/dev" }
            registry.add("streampack.dictionary.timeout") { "500ms" }
        }

        @JvmStatic @AfterAll fun stop() = stub.close()
    }

    private fun message(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local"),
            )
            .build()

    private fun define(text: String): String {
        val result = eventGateway.process(message(text))
        return (assertInstanceOf(OperationResult.Success::class.java, result)).payload.toString()
    }

    @BeforeEach
    fun setUp() {
        stub.clear()
        lookupService.resetBreakers()
    }

    @Test
    fun `define answers from FreeDictionaryAPI, with its credit`() {
        stub.answer(
            "/free/ephemeral",
            200,
            freeDictionaryJson("ephemeral", "adjective", "lasting for a very short time."),
        )

        val payload = define("define ephemeral")
        assertTrue(
            payload.contains("ephemeral (adjective): lasting for a very short time."),
            payload,
        )
        assertTrue(
            payload.contains(
                "(Wiktionary via FreeDictionaryAPI.com, CC BY-SA 4.0: https://en.wiktionary.org/wiki/ephemeral)",
            ),
            payload,
        )
    }

    @Test
    fun `with FreeDictionaryAPI failing, dictionaryapi dev answers`() {
        stub.answer("/free/ephemeral", 503)
        stub.answer(
            "/dev/ephemeral",
            200,
            dictionaryJson("ephemeral", "adjective", "lasting for a very short time."),
        )

        val payload = define("define ephemeral")
        assertTrue(payload.contains("(Wiktionary via dictionaryapi.dev, CC BY-SA 3.0:"), payload)
    }

    @Test
    fun `with FreeDictionaryAPI hanging, the next provider still answers quickly`() {
        stub.hang("/free/ephemeral")
        stub.answer(
            "/dev/ephemeral",
            200,
            dictionaryJson("ephemeral", "adjective", "lasting for a very short time."),
        )

        val started = System.nanoTime()
        define("define ephemeral")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun `the cached factoid keeps the credit`() {
        stub.answer(
            "/free/transient",
            200,
            freeDictionaryJson("transient", "adjective", "lasting only for a short time."),
        )
        define("define transient")
        stub.clear()

        val payload = define("define transient")
        assertTrue(payload.contains("lasting only for a short time."), payload)
        assertTrue(payload.contains("(Wiktionary via FreeDictionaryAPI.com"), payload)
    }

    @Test
    fun `a definition too long to cache with its credit is answered but not cached`() {
        val definition = "very long. ".repeat(45).trim()
        stub.answer("/free/verbose", 200, freeDictionaryJson("verbose", "adjective", definition))
        assertTrue(define("define verbose").contains("FreeDictionaryAPI.com"))

        stub.answer("/free/verbose", 200, freeDictionaryJson("verbose"))
        assertInstanceOf(
            OperationResult.NotHandled::class.java,
            eventGateway.process(message("define verbose")),
        )
    }

    @Test
    fun `define lookup is case insensitive`() {
        stub.answer(
            "/free/ephemeral",
            200,
            freeDictionaryJson("ephemeral", "adjective", "lasting for a very short time."),
        )
        define("Define EPHEMERAL")
    }

    @Test
    fun `an unknown word is not handled, and the next provider isn't asked`() {
        stub.answer("/free/xyzzyplugh", 200, freeDictionaryJson("xyzzyplugh"))
        stub.answer(
            "/dev/xyzzyplugh",
            200,
            dictionaryJson("xyzzyplugh", "noun", "should not be asked"),
        )

        assertInstanceOf(
            OperationResult.NotHandled::class.java,
            eventGateway.process(message("define xyzzyplugh")),
        )
    }

    @Test
    fun `non-matching input is not handled`() {
        assertInstanceOf(
            OperationResult.NotHandled::class.java,
            eventGateway.process(message("defin ephemeral")),
        )
    }

    @Test
    fun `plain text is not handled`() {
        assertInstanceOf(
            OperationResult.NotHandled::class.java,
            eventGateway.process(message("hello world")),
        )
    }

    @Test
    fun `triggered define lookup is handled`() {
        stub.answer(
            "/free/ephemeral",
            200,
            freeDictionaryJson("ephemeral", "adjective", "lasting for a very short time."),
        )
        define("!define ephemeral")
    }

    @Test
    fun `an acronym Wiktionary has in capitals is found, and cached under lowercase`() {
        stub.answer("/free/json", 200, freeDictionaryJson("json"))
        stub.answer("/free/Json", 200, freeDictionaryJson("Json"))
        stub.answer(
            "/free/JSON",
            200,
            freeDictionaryJson("JSON", "noun", "JavaScript Object Notation."),
        )

        assertTrue(define("define json").contains("JSON (noun): JavaScript Object Notation."))
        stub.clear()
        assertTrue(
            define("define JSON").contains("JavaScript Object Notation."),
            "served from the factoid",
        )
    }
}
