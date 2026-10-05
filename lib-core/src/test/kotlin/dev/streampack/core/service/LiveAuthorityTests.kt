/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EgressSubscriber
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.UserRepository
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/**
 * Live authority at the chain's entry, and sanitized answers to failed commands, for messages that
 * ask for them (#115); messages that don't keep the existing behaviour.
 */
@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class, LiveAuthorityTests.TestOps::class)
class LiveAuthorityTests {

    @TestConfiguration
    class TestOps {
        @Bean fun seen() = CopyOnWriteArrayList<UserPrincipal?>()

        /** "whoami" answers with the role the operations see. */
        @Bean
        fun whoami(seen: CopyOnWriteArrayList<UserPrincipal?>) =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>) = message.payload == "whoami"

                override fun execute(message: Message<*>): OperationOutcome {
                    val user = (message.headers[Provenance.HEADER] as Provenance).user
                    seen.add(user)
                    return OperationResult.Success(user?.role?.name ?: "anonymous")
                }
            }

        /** "boom" fails unexpectedly, with something in its message no client should see. */
        @Bean
        fun boom() =
            object : Operation {
                override val priority = 10

                override fun canHandle(message: Message<*>) = message.payload == "boom"

                override fun execute(message: Message<*>): OperationOutcome =
                    throw IllegalStateException("secret internals at line 42")
            }

        @Bean fun webConsoleCatcher() = Catcher()
    }

    /** The web console's egress, as the operations left it. */
    class Catcher : EgressSubscriber() {
        val received = CopyOnWriteArrayList<Pair<OperationResult, Provenance>>()

        override fun matches(provenance: Provenance) = provenance.protocol == Protocol.WEBCONSOLE

        override fun deliver(result: OperationResult, provenance: Provenance) {
            received.add(result to provenance)
        }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var seen: CopyOnWriteArrayList<UserPrincipal?>
    @Autowired lateinit var catcher: Catcher

    @BeforeEach
    fun reset() {
        seen.clear()
        catcher.received.clear()
    }

    private fun user(role: Role, status: UserStatus = UserStatus.ACTIVE) =
        userRepository.save(
            User(
                username = "u-${UUID.randomUUID().toString().take(8)}",
                email = "${UUID.randomUUID()}@example.com",
                displayName = "Someone",
                role = role,
                status = status,
            )
        )

    private fun message(
        text: String,
        principal: UserPrincipal?,
        live: Boolean = true,
        report: Boolean = false,
    ): Message<String> =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.WEBCONSOLE,
                    serviceId = "web",
                    replyTo = "users/${principal?.id}",
                    user = principal,
                    correlationId = "c-1",
                ),
            )
            .setHeader(Provenance.ADDRESSED, true)
            .setHeader(Provenance.LIVE_AUTHORITY, live)
            .setHeader(Provenance.REPORT_FAILURES, report)
            .build()

    @Test
    fun `the operations see the account as it is now, not as the credential had it`() {
        val admin = user(Role.ADMIN)
        // Issued while an admin; demoted since (or while the command was queued).
        val credential = admin.toUserPrincipal()
        userRepository.save(admin.copy(role = Role.USER))

        val result = eventGateway.process(message("whoami", credential))

        assertEquals(OperationResult.Success("USER"), result)
        assertEquals(Role.USER, seen.single()?.role)
        // Egress carries the refreshed principal too, not the stale one.
        assertEquals(Role.USER, catcher.received.single().second.user?.role)
    }

    @Test
    fun `an account suspended or gone is refused before any operation runs`() {
        val suspended =
            user(Role.ADMIN).let { userRepository.save(it.copy(status = UserStatus.SUSPENDED)) }
        val stranger = UserPrincipal(UUID.randomUUID(), "ghost", "Ghost", Role.SUPER_ADMIN)

        for (principal in listOf(suspended.toUserPrincipal(), stranger)) {
            val result = eventGateway.process(message("whoami", principal))
            assertEquals(OperationResult.Error(OperationService.INACTIVE_ACCOUNT), result)
        }
        assertTrue(seen.isEmpty())
        assertEquals(2, catcher.received.size)
    }

    @Test
    fun `without the header, the principal is taken as it arrived`() {
        val claimed = UserPrincipal(UUID.randomUUID(), "claimed", "Claimed", Role.ADMIN)

        val result = eventGateway.process(message("whoami", claimed, live = false))

        assertEquals(OperationResult.Success("ADMIN"), result)
    }

    @Test
    fun `a message with no principal stays anonymous`() {
        assertEquals(
            OperationResult.Success("anonymous"),
            eventGateway.process(message("whoami", null)),
        )
    }

    @Test
    fun `a failed command that asks is answered with a sanitized error, correlated`() {
        val admin = user(Role.ADMIN).toUserPrincipal()

        val result = eventGateway.process(message("boom", admin, report = true))

        assertEquals(OperationResult.Error(OperationService.COMMAND_FAILED), result)
        val (sent, provenance) = catcher.received.single()
        assertEquals(OperationResult.Error(OperationService.COMMAND_FAILED), sent)
        assertEquals("c-1", provenance.correlationId)
        assertTrue("secret" !in (sent as OperationResult.Error).message)
    }

    @Test
    fun `a failed command that doesn't ask fails as before`() {
        val admin = user(Role.ADMIN).toUserPrincipal()

        assertThrows(Exception::class.java) { eventGateway.process(message("boom", admin)) }
        assertTrue(catcher.received.isEmpty())
    }
}
