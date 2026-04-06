/* Joseph B. Ottinger (C)2026 */
package dev.streampack.safecracker.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.safecracker.model.SafecrackerGameState
import dev.streampack.safecracker.service.SafecrackerTimerService
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.MessageChannel
import org.springframework.messaging.support.MessageBuilder
import tools.jackson.module.kotlin.convertValue

@SpringBootTest
class SafecrackerOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var stateService: ProvenanceStateService
    @Autowired lateinit var timerService: SafecrackerTimerService

    @Qualifier("tickChannel") @Autowired lateinit var tickChannel: MessageChannel

    private val objectMapper = JacksonMappers.standard()

    private val provenance =
        Provenance(
            protocol = Protocol.CONSOLE,
            serviceId = "",
            replyTo = "local",
            user =
                UserPrincipal(
                    id = UUID.randomUUID(),
                    username = "testplayer",
                    displayName = "testplayer",
                ),
        )
    private val provenanceUri = provenance.encode()

    private fun safecrackerMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance)
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @BeforeEach
    fun cleanup() {
        stateService.clearState(provenanceUri, SafecrackerGameState.STATE_KEY)
        timerService.unregisterGame(provenanceUri)
    }

    @Test
    fun `start new game creates state and shows pattern`() {
        val result = eventGateway.process(safecrackerMessage("safecracker"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("safecracker pattern:"))
        assertTrue(payload.contains(". . . ."))
        assertTrue(payload.contains("5:00"))

        val state = stateService.getState(provenanceUri, SafecrackerGameState.STATE_KEY)
        assertNotNull(state)
    }

    @Test
    fun `safecracker with active game shows time remaining`() {
        eventGateway.process(safecrackerMessage("safecracker"))
        val result = eventGateway.process(safecrackerMessage("safecracker"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("game in progress"))
        assertTrue(payload.contains("to go!"))
    }

    @Test
    fun `correct guess wins the game`() {
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), Instant.now().epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )

        val result = eventGateway.process(safecrackerMessage("safecracker 1 2 3 4"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("broken the code"))
        assertTrue(payload.contains("testplayer"))

        val cleared = stateService.getState(provenanceUri, SafecrackerGameState.STATE_KEY)
        assertNull(cleared)
    }

    @Test
    fun `incorrect guess shows feedback`() {
        val state = SafecrackerGameState(listOf(2, 5, 3, 7), Instant.now().epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )

        val result = eventGateway.process(safecrackerMessage("safecracker 3 5 1 9"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("> = < >"))
        assertTrue(payload.contains("testplayer"))
        assertTrue(payload.contains("to go!"))
    }

    @Test
    fun `all-correct feedback`() {
        val state = SafecrackerGameState(listOf(0, 0, 0, 0), Instant.now().epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )

        val result = eventGateway.process(safecrackerMessage("safecracker 0 0 0 0"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("broken the code"))
    }

    @Test
    fun `guess with no active game returns error`() {
        val result = eventGateway.process(safecrackerMessage("safecracker 1 2 3 4"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("No game in progress"))
    }

    @Test
    fun `invalid guess format returns error`() {
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), Instant.now().epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )

        val result = eventGateway.process(safecrackerMessage("safecracker 1 2 3"))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val message = (result as OperationResult.Error).message
        assertTrue(message.contains("4 digits"))
    }

    @Test
    fun `guess with non-numeric input returns error`() {
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), Instant.now().epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )

        val result = eventGateway.process(safecrackerMessage("safecracker a b c d"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `guess with out-of-range digits returns error`() {
        val state = SafecrackerGameState(listOf(1, 2, 3, 4), Instant.now().epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )

        val result = eventGateway.process(safecrackerMessage("safecracker 1 2 3 10"))
        assertInstanceOf(OperationResult.Error::class.java, result)
    }

    @Test
    fun `concede reveals answer and clears state`() {
        val state = SafecrackerGameState(listOf(7, 8, 9, 0), Instant.now().epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )

        val result = eventGateway.process(safecrackerMessage("safecracker concede"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("concede"))
        assertTrue(payload.contains("7 8 9 0"))

        val cleared = stateService.getState(provenanceUri, SafecrackerGameState.STATE_KEY)
        assertNull(cleared)
    }

    @Test
    fun `concede with no active game returns graceful message`() {
        val result = eventGateway.process(safecrackerMessage("safecracker concede"))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("No game in progress"))
    }

    @Test
    fun `non-safecracker message is not handled`() {
        val result = eventGateway.process(safecrackerMessage("calc 2+3"))
        if (result is OperationResult.Success) {
            val payload = result.payload as String
            assertTrue(!payload.contains("safecracker", ignoreCase = true))
        }
    }

    @Test
    fun `timer timeout clears game and reveals answer`() {
        // Seed a game that started 6 minutes ago (already expired)
        val expiredStart = Instant.now().minus(Duration.ofMinutes(6))
        val state = SafecrackerGameState(listOf(4, 5, 6, 7), expiredStart.epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )
        timerService.registerGame(provenanceUri, expiredStart)

        // Send a tick to trigger timeout
        tickChannel.send(MessageBuilder.withPayload(Instant.now()).build())

        // State should be cleared
        val cleared = stateService.getState(provenanceUri, SafecrackerGameState.STATE_KEY)
        assertNull(cleared)
    }

    @Test
    fun `feedback directions are correct for all positions`() {
        val state = SafecrackerGameState(listOf(5, 5, 5, 5), Instant.now().epochSecond)
        stateService.setState(
            provenanceUri,
            SafecrackerGameState.STATE_KEY,
            objectMapper.convertValue<Map<String, Any>>(state),
        )

        // Guess lower in all positions
        var result = eventGateway.process(safecrackerMessage("safecracker 1 1 1 1"))
        var payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("< < < <"))

        // Guess higher in all positions
        result = eventGateway.process(safecrackerMessage("safecracker 9 9 9 9"))
        payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("> > > >"))

        // Guess exact
        result = eventGateway.process(safecrackerMessage("safecracker 5 5 5 5"))
        payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("broken the code"))
    }
}
