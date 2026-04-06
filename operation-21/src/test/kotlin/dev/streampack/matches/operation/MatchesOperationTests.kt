/* Joseph B. Ottinger (C)2026 */
package dev.streampack.matches.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.matches.model.MatchesGameState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class MatchesOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var stateService: ProvenanceStateService

    private val provenance =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local")
    private val provenanceUri = provenance.encode()

    private fun matchesMessage(text: String) =
        MessageBuilder.withPayload(text).setHeader(Provenance.HEADER, provenance).build()

    @BeforeEach
    fun cleanup() {
        stateService.clearState(provenanceUri, MatchesGameState.STATE_KEY)
    }

    @Test
    fun `start game creates state with 21 matches`() {
        val result = eventGateway.process(matchesMessage("21 matches"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("21"))

        val state = stateService.getState(provenanceUri, MatchesGameState.STATE_KEY)
        assertNotNull(state)
        assertEquals(21, (state!!["remaining"] as Number).toInt())
    }

    @Test
    fun `start game when already active returns error`() {
        eventGateway.process(matchesMessage("21 matches"))
        val result = eventGateway.process(matchesMessage("21 matches"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("already in progress"))
    }

    @Test
    fun `take valid number updates state correctly`() {
        eventGateway.process(matchesMessage("21 matches"))
        val result = eventGateway.process(matchesMessage("21 take 2"))
        assertInstanceOf(OperationResult.Success::class.java, result)

        // Player takes 2, bot takes 2 (4-2), so 21 - 2 - 2 = 17
        val state = stateService.getState(provenanceUri, MatchesGameState.STATE_KEY)
        assertNotNull(state)
        assertEquals(17, (state!!["remaining"] as Number).toInt())
    }

    @Test
    fun `take zero returns error`() {
        eventGateway.process(matchesMessage("21 matches"))
        val result = eventGateway.process(matchesMessage("21 take 0"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `take four returns error`() {
        eventGateway.process(matchesMessage("21 matches"))
        val result = eventGateway.process(matchesMessage("21 take 4"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `take non-numeric returns error`() {
        eventGateway.process(matchesMessage("21 matches"))
        val result = eventGateway.process(matchesMessage("21 take abc"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("number"))
    }

    @Test
    fun `take more than remaining returns error`() {
        eventGateway.process(matchesMessage("21 matches"))
        // Play down to 5 remaining: take 1 four times (each round removes 4 total)
        eventGateway.process(matchesMessage("21 take 1")) // 21 -> 17
        eventGateway.process(matchesMessage("21 take 1")) // 17 -> 13
        eventGateway.process(matchesMessage("21 take 1")) // 13 -> 9
        eventGateway.process(matchesMessage("21 take 1")) // 9 -> 5

        val state = stateService.getState(provenanceUri, MatchesGameState.STATE_KEY)
        assertNotNull(state)
        assertEquals(5, (state!!["remaining"] as Number).toInt())

        // No error since 3 <= 5, but let's not test that here;
        // instead directly set state to a low number
        stateService.setState(provenanceUri, MatchesGameState.STATE_KEY, mapOf("remaining" to 2))

        val result = eventGateway.process(matchesMessage("21 take 3"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("only 2"))
    }

    @Test
    fun `take with no active game returns error`() {
        val result = eventGateway.process(matchesMessage("21 take 2"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("No game in progress"))
    }

    @Test
    fun `concede active game clears state`() {
        eventGateway.process(matchesMessage("21 matches"))
        val result = eventGateway.process(matchesMessage("21 concede"))
        assertInstanceOf(OperationResult.Success::class.java, result)

        val state = stateService.getState(provenanceUri, MatchesGameState.STATE_KEY)
        assertNull(state)
    }

    @Test
    fun `concede with no active game returns graceful message`() {
        val result = eventGateway.process(matchesMessage("21 concede"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("No game in progress"))
    }

    @Test
    fun `full game playthrough ends in bot victory`() {
        eventGateway.process(matchesMessage("21 matches"))

        // Bot uses 4-N strategy, so each round removes exactly 4.
        // 21 -> 17 -> 13 -> 9 -> 5 -> 1 (bot wins)
        // Player takes 1 each time, bot takes 3 each time.
        eventGateway.process(matchesMessage("21 take 1")) // 21 - 1 - 3 = 17
        eventGateway.process(matchesMessage("21 take 1")) // 17 - 1 - 3 = 13
        eventGateway.process(matchesMessage("21 take 1")) // 13 - 1 - 3 = 9
        eventGateway.process(matchesMessage("21 take 1")) // 9 - 1 - 3 = 5

        val result = eventGateway.process(matchesMessage("21 take 1")) // 5 - 1 - 3 = 1
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(
            MatchesOperation.VICTORY_LINES.any { payload.contains(it) },
            "Expected payload to contain a victory line, but was: $payload",
        )

        // State should be cleared after victory
        val state = stateService.getState(provenanceUri, MatchesGameState.STATE_KEY)
        assertNull(state)
    }

    @Test
    fun `unknown subcommand returns usage hint`() {
        val result = eventGateway.process(matchesMessage("21 foobar"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("Usage"))
    }

    @Test
    fun `non-21 message is not handled`() {
        val result = eventGateway.process(matchesMessage("calc 2+3"))
        // Should not be handled by MatchesOperation
        val isNotMatches =
            result !is OperationResult.Success ||
                !(result.payload as String).contains("matches", ignoreCase = true)
        assertTrue(isNotMatches)
    }
}
