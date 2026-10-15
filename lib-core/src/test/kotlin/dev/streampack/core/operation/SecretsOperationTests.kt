/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.SecretRequirement
import dev.streampack.core.model.SecretRequirementsRequest
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.service.SecretRequirementSource
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.support.MessageBuilder

/** The `secrets` command and its typed request: which variables the next start needs (#209). */
@SpringBootTest
class SecretsOperationTests {

    @TestConfiguration
    class Sources {
        @Bean fun fakeSecrets(): FakeSource = FakeSource()
    }

    class FakeSource : SecretRequirementSource {
        var needed: List<SecretRequirement> = emptyList()

        override fun secretRequirements(): List<SecretRequirement> = needed
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var source: FakeSource

    private val home =
        SecretRequirement(
            "MATTERMOST_HOME_TOKEN",
            "Mattermost server 'home' token",
            SecretRequirement.State.STORED_IN_DATABASE,
        )
    private val libera =
        SecretRequirement(
            "IRC_LIBERA_SASL_PASSWORD",
            "IRC network 'libera' SASL password",
            SecretRequirement.State.NOT_SET,
        )

    @BeforeEach
    fun reset() {
        source.needed = listOf(home, libera)
    }

    private fun send(payload: Any, role: Role) =
        eventGateway.process(
            MessageBuilder.withPayload(payload)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.CONSOLE,
                        serviceId = "",
                        replyTo = "local",
                        user = UserPrincipal(UUID.randomUUID(), "admin", "Admin", role),
                    ),
                )
                .setHeader(Provenance.ADDRESSED, true)
                .build(),
        )

    @Test
    fun `lists each variable by name, sorted, with what it's for and never a value`() {
        val result = send("secrets", Role.SUPER_ADMIN)
        assertInstanceOf(OperationResult.Success::class.java, result)
        val text = (result as OperationResult.Success).payload as String
        val lines = text.lines()
        assertTrue(lines.first().contains("2"), text)
        assertTrue(lines[1].startsWith("IRC_LIBERA_SASL_PASSWORD"), text)
        assertTrue(lines[1].contains("not set"), text)
        assertTrue(lines[2].startsWith("MATTERMOST_HOME_TOKEN"), text)
        assertTrue(lines[2].contains("stored in the database"), text)
    }

    @Test
    fun `says so when nothing is needed`() {
        source.needed = emptyList()
        val result = send("secrets", Role.SUPER_ADMIN) as OperationResult.Success
        assertTrue(
            (result.payload as String).contains("No environment variables"),
            result.payload as String,
        )
    }

    @Test
    fun `answers a typed request with the list itself`() {
        val result = send(SecretRequirementsRequest, Role.SUPER_ADMIN) as OperationResult.Success
        assertEquals(listOf(libera, home), result.payload)
    }

    @Test
    fun `is for super-admins only`() {
        for (role in listOf(Role.ADMIN, Role.USER)) {
            val result = send("secrets", role)
            assertInstanceOf(OperationResult.Error::class.java, result)
            assertTrue(
                (result as OperationResult.Error).message.contains("Insufficient privileges"),
            )
        }
    }
}
