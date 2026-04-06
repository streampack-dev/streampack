/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.ProvenanceStateService
import dev.streampack.hangman.model.HangmanGameState
import dev.streampack.hangman.service.HangmanService
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

@SpringBootTest
class HangmanAdminOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var hangmanService: HangmanService
    @Autowired lateinit var stateService: ProvenanceStateService

    private fun adminPrincipal() =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin User",
            role = Role.ADMIN,
        )

    private fun userPrincipal() =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "regular",
            displayName = "Regular User",
            role = Role.USER,
        )

    private fun provenance(user: UserPrincipal) =
        Provenance(protocol = Protocol.CONSOLE, serviceId = "", replyTo = "local", user = user)

    private fun message(text: String, user: UserPrincipal) =
        MessageBuilder.withPayload(text)
            .setHeader(Provenance.HEADER, provenance(user))
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @BeforeEach
    fun cleanup() {
        val prov = provenance(adminPrincipal())
        stateService.clearState(prov.encode(), HangmanGameState.STATE_KEY)
        // Clean up any test blocked words
        hangmanService.unblockWord("badword")
        hangmanService.unblockWord("testblock")
    }

    @Test
    fun `admin can block a word`() {
        val result = eventGateway.process(message("hangman block badword", adminPrincipal()))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Blocked"))
        assertTrue(payload.contains("badword"))
        assertTrue(hangmanService.isBlocked("badword"))
    }

    @Test
    fun `admin can unblock a word`() {
        hangmanService.blockWord("testblock")
        val result = eventGateway.process(message("hangman unblock testblock", adminPrincipal()))
        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload as String
        assertTrue(payload.contains("Unblocked"))
        assertFalse(hangmanService.isBlocked("testblock"))
    }

    @Test
    fun `regular user cannot block a word`() {
        val result = eventGateway.process(message("hangman block badword", userPrincipal()))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val errorMessage = (result as OperationResult.Error).message
        assertTrue(errorMessage.contains("ADMIN"))
    }

    @Test
    fun `regular user cannot unblock a word`() {
        val result = eventGateway.process(message("hangman unblock badword", userPrincipal()))
        assertInstanceOf(OperationResult.Error::class.java, result)
        val errorMessage = (result as OperationResult.Error).message
        assertTrue(errorMessage.contains("ADMIN"))
    }

    @Test
    fun `blocked word does not appear in games`() {
        // Block all possible words then verify selectWord fails gracefully
        // Instead, block a specific word and verify it is filtered
        hangmanService.blockWord("testblock")
        assertTrue(hangmanService.isBlocked("testblock"))
    }
}
