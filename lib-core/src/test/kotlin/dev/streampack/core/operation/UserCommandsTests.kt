/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.ServiceIdentity
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.model.UserStatus
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.DirectConversations
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** `list users`, `show user` and `whoami` (#247) */
@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class)
class UserCommandsTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var dana: UserPrincipal
    private lateinit var admin: UserPrincipal
    private lateinit var superAdmin: UserPrincipal

    private val danaOnMattermost = ServiceIdentity("firenet", "dana0000000000000000000000")

    @BeforeEach
    fun setUp() {
        fun register(name: String, display: String, role: Role) =
            userRegistrationService.register(
                username = name,
                email = "$name@example.com",
                displayName = display,
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = name,
                role = role,
            )
        dana = register("dana", "Dana Kim", Role.USER)
        admin = register("cmdadmin", "Command Admin", Role.ADMIN)
        superAdmin = register("cmdsuper", "Command Super", Role.SUPER_ADMIN)
        userRegistrationService.linkProtocol(
            userId = dana.id,
            protocol = Protocol.MATTERMOST,
            serviceId = danaOnMattermost.serviceId,
            externalIdentifier = danaOnMattermost.externalIdentifier,
        )
    }

    private fun console(text: String, user: UserPrincipal?) =
        eventGateway.process(
            MessageBuilder.withPayload(text)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.CONSOLE,
                        serviceId = "console",
                        replyTo = "console",
                        user = user,
                    ),
                )
                .build(),
        )

    /** As a Mattermost adapter sends it: in a DM (channel type D) or a channel (O) */
    private fun mattermost(
        text: String,
        identity: ServiceIdentity,
        user: UserPrincipal?,
        direct: Boolean = true,
    ) =
        eventGateway.process(
            MessageBuilder.withPayload(text)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.MATTERMOST,
                        serviceId = identity.serviceId,
                        replyTo = "chan0000000000000000000000",
                        user = user,
                        metadata =
                            mapOf(DirectConversations.CHANNEL_TYPE to if (direct) "D" else "O"),
                    ),
                )
                .setHeader(Provenance.IDENTITY, identity)
                .build(),
        )

    private fun text(result: Any?): String =
        when (result) {
            is OperationResult.Success -> result.payload.toString()
            is OperationResult.Error -> "Error: ${result.message}"
            else -> result.toString()
        }

    @Test
    fun `list users shows each account, and emails only to a super admin`() {
        val asSuper = text(console("list users", superAdmin))
        assertTrue(
            asSuper.contains("  dana (Dana Kim): user, active, 2 linked, dana@example.com"),
            asSuper,
        )
        assertTrue(asSuper.contains("  cmdadmin (Command Admin): admin, active, 1 linked"), asSuper)

        val asAdmin = text(console("list users", admin))
        assertTrue(asAdmin.contains("  dana (Dana Kim): user, active, 2 linked\n"), asAdmin)
        assertFalse(asAdmin.contains("@example.com"), asAdmin)
    }

    @Test
    fun `list users narrows by role, and refuses a role that doesn't exist`() {
        val admins = text(console("list users admin", superAdmin))
        assertTrue(admins.contains("cmdadmin"), admins)
        assertFalse(admins.contains("dana"), admins)
        assertEquals(
            "Error: Unknown role 'chief': use guest, user, admin, super_admin",
            text(console("list users chief", superAdmin)),
        )
    }

    @Test
    fun `list users and show user need an admin`() {
        assertEquals(
            "Error: Insufficient privileges: requires ADMIN",
            text(console("list users", dana)),
        )
        assertEquals(
            "Error: Insufficient privileges: requires ADMIN",
            text(console("show user dana", dana)),
        )
    }

    @Test
    fun `list users shows suspended accounts as suspended`() {
        val user = userRepository.findByUsername("dana")!!
        userRepository.saveAndFlush(user.copy(status = UserStatus.SUSPENDED))
        val listed = text(console("list users user", superAdmin))
        assertTrue(listed.contains("dana (Dana Kim): user, suspended"), listed)
    }

    @Test
    fun `show user lists linked identities as unlink takes them`() {
        assertEquals(
            """
            dana (Dana Kim)
              Role: user
              State: active
              Email: dana@example.com (not verified)
              Linked identities:
                http test-service dana
                mattermost firenet dana0000000000000000000000
            """
                .trimIndent(),
            text(console("show user dana", superAdmin)),
        )
        assertFalse(text(console("show user dana", admin)).contains("Email"))
        assertEquals("Error: User not found", text(console("show user nobody", superAdmin)))
    }

    @Test
    fun `whoami on the console names the account and role`() {
        assertEquals(
            "You're cmdsuper (Command Super), super_admin.",
            text(console("whoami", superAdmin)),
        )
        assertEquals(
            "You're not signed in. Sign in, or link this identity to an account.",
            text(console("who am I", null)),
        )
    }

    @Test
    fun `whoami in a DM says how a linked identity is linked`() {
        assertEquals(
            "You're dana (Dana Kim), user. Linked as mattermost firenet dana0000000000000000000000.",
            text(mattermost("whoami", danaOnMattermost, dana)),
        )
    }

    @Test
    fun `whoami in a DM gives an unlinked sender the line to link them`() {
        val stranger = ServiceIdentity("firenet", "str4nger00000000000000000a")
        assertEquals(
            "You're not linked to an account. An admin can link you with: " +
                "link user <username> mattermost firenet str4nger00000000000000000a",
            text(mattermost("whoami", stranger, null)),
        )
    }

    @Test
    fun `whoami says when the linked account is suspended`() {
        val user = userRepository.findByUsername("dana")!!
        userRepository.saveAndFlush(user.copy(status = UserStatus.SUSPENDED))
        assertEquals(
            "This identity is linked to dana, whose account is suspended.",
            text(mattermost("whoami", danaOnMattermost, null)),
        )
    }

    @Test
    fun `whoami in a channel announces nothing`() {
        assertEquals(
            "Ask me that in a direct message.",
            text(mattermost("whoami", danaOnMattermost, dana, direct = false)),
        )
    }
}
