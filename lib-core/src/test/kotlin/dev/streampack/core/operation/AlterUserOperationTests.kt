/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import com.enigmastation.streampack.core.TestChannelConfiguration
import com.enigmastation.streampack.core.integration.EventGateway
import com.enigmastation.streampack.core.model.AlterUserRequest
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.model.UserPrincipal
import com.enigmastation.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Integration tests for AlterUserOperation authorization and field updates */
@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class)
class AlterUserOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService

    private lateinit var regularUser: UserPrincipal
    private lateinit var adminUser: UserPrincipal
    private lateinit var superAdmin: UserPrincipal

    @BeforeEach
    fun setUp() {
        regularUser =
            userRegistrationService.register(
                username = "regularuser",
                email = "regular@example.com",
                displayName = "Regular User",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "regularuser",
            )
        adminUser =
            userRegistrationService.register(
                username = "adminuser",
                email = "admin@example.com",
                displayName = "Admin User",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "adminuser",
                role = Role.ADMIN,
            )
        superAdmin =
            userRegistrationService.register(
                username = "superuser",
                email = "super@example.com",
                displayName = "Super Admin",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "superuser",
                role = Role.SUPER_ADMIN,
            )
    }

    private fun alterUserMessage(request: AlterUserRequest, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "test-service",
                    replyTo = "admin/alter-user",
                    user = asUser,
                ),
            )
            .build()

    private fun textMessage(text: String, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.CONSOLE,
                    serviceId = "",
                    replyTo = "local",
                    user = asUser,
                ),
            )
            .setHeader(Provenance.ADDRESSED, true)
            .build()

    @Test
    fun `super admin can change user role`() {
        val request = AlterUserRequest(username = "regularuser", role = Role.ADMIN)
        val result = eventGateway.process(alterUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("regularuser", principal.username)
        assertEquals(Role.ADMIN, principal.role)
    }

    @Test
    fun `super admin can change user displayName`() {
        val request = AlterUserRequest(username = "regularuser", displayName = "Updated Name")
        val result = eventGateway.process(alterUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("Updated Name", principal.displayName)
    }

    @Test
    fun `super admin can change user email`() {
        val request = AlterUserRequest(username = "regularuser", email = "new@example.com")
        val result = eventGateway.process(alterUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `super admin can change user username`() {
        val request = AlterUserRequest(username = "regularuser", newUsername = "renamed")
        val result = eventGateway.process(alterUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("renamed", principal.username)
    }

    @Test
    fun `admin can change user role to guest`() {
        val request = AlterUserRequest(username = "regularuser", role = Role.GUEST)
        val result = eventGateway.process(alterUserMessage(request, adminUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals(Role.GUEST, principal.role)
    }

    @Test
    fun `admin cannot change user role to admin`() {
        val request = AlterUserRequest(username = "regularuser", role = Role.ADMIN)
        val result = eventGateway.process(alterUserMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `admin cannot modify another admin`() {
        // Create a second admin to target
        val otherAdmin =
            userRegistrationService.register(
                username = "otheradmin",
                email = "otheradmin@example.com",
                displayName = "Other Admin",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "otheradmin",
                role = Role.ADMIN,
            )

        val request = AlterUserRequest(username = "otheradmin", displayName = "Hacked")
        val result = eventGateway.process(alterUserMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `regular user cannot alter anyone`() {
        val request = AlterUserRequest(username = "adminuser", role = Role.USER)
        val result = eventGateway.process(alterUserMessage(request, regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `cannot change own role`() {
        val request = AlterUserRequest(username = "superuser", role = Role.USER)
        val result = eventGateway.process(alterUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Cannot change own role", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent target returns error`() {
        val request = AlterUserRequest(username = "nobody", role = Role.ADMIN)
        val result = eventGateway.process(alterUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `duplicate username on rename returns error`() {
        val request = AlterUserRequest(username = "regularuser", newUsername = "adminuser")
        val result = eventGateway.process(alterUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Username already exists", (result as OperationResult.Error).message)
    }

    // -- Text translation path --

    @Test
    fun `text command changes role`() {
        val result =
            eventGateway.process(textMessage("alter user regularuser role admin", superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals(Role.ADMIN, principal.role)
    }

    @Test
    fun `text command changes email`() {
        val result =
            eventGateway.process(
                textMessage("alter user regularuser email new@example.com", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
    }

    @Test
    fun `text command changes displayname`() {
        val result =
            eventGateway.process(
                textMessage("alter user regularuser displayname New Display Name", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("New Display Name", principal.displayName)
    }

    @Test
    fun `text command changes username`() {
        val result =
            eventGateway.process(textMessage("alter user regularuser username newname", superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("newname", principal.username)
    }

    @Test
    fun `text command with invalid role is not handled`() {
        val result =
            eventGateway.process(textMessage("alter user regularuser role bogus", superAdmin))

        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `text command with unknown field is not handled`() {
        val result =
            eventGateway.process(textMessage("alter user regularuser phone 555-1234", superAdmin))

        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `text command enforces authorization`() {
        val result =
            eventGateway.process(textMessage("alter user adminuser role user", regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `text command with hyphenated role works`() {
        val result =
            eventGateway.process(textMessage("alter user regularuser role super-admin", superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals(Role.SUPER_ADMIN, principal.role)
    }
}
