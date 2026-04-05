/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import com.enigmastation.streampack.core.TestChannelConfiguration
import com.enigmastation.streampack.core.integration.EventGateway
import com.enigmastation.streampack.core.model.CreateUserRequest
import com.enigmastation.streampack.core.model.OperationResult
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Provenance
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.model.UserPrincipal
import com.enigmastation.streampack.core.repository.ServiceBindingRepository
import com.enigmastation.streampack.core.repository.UserRepository
import com.enigmastation.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Integration tests for admin user provisioning via CreateUserOperation */
@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class)
class CreateUserOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var serviceBindingRepository: ServiceBindingRepository

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

    private fun createUserMessage(request: CreateUserRequest, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "test-service",
                    replyTo = "admin/create-user",
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
                    serviceId = "console",
                    replyTo = "console",
                    user = asUser,
                ),
            )
            .build()

    @Test
    fun `super admin can create a user`() {
        val request =
            CreateUserRequest(
                username = "newuser",
                email = "newuser@example.com",
                displayName = "New User",
            )
        val result = eventGateway.process(createUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("newuser", principal.username)
        assertEquals("New User", principal.displayName)
        assertEquals(Role.USER, principal.role)
    }

    @Test
    fun `super admin can create user with non-default role`() {
        val request =
            CreateUserRequest(
                username = "newadmin",
                email = "newadmin@example.com",
                displayName = "New Admin",
                role = Role.ADMIN,
            )
        val result = eventGateway.process(createUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals(Role.ADMIN, principal.role)
    }

    @Test
    fun `created user has emailVerified true and no bindings`() {
        val request =
            CreateUserRequest(
                username = "verified",
                email = "verified@example.com",
                displayName = "Verified User",
            )
        eventGateway.process(createUserMessage(request, superAdmin))

        val user = userRepository.findByUsername("verified")
        assertNotNull(user)
        assertTrue(user!!.emailVerified)

        val bindings = serviceBindingRepository.findAll().filter { it.user.id == user.id }
        assertTrue(bindings.isEmpty())
    }

    @Test
    fun `regular user cannot create users`() {
        val request =
            CreateUserRequest(
                username = "newuser",
                email = "newuser@example.com",
                displayName = "New User",
            )
        val result = eventGateway.process(createUserMessage(request, regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `admin cannot create users`() {
        val request =
            CreateUserRequest(
                username = "newuser",
                email = "newuser@example.com",
                displayName = "New User",
            )
        val result = eventGateway.process(createUserMessage(request, adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val request =
            CreateUserRequest(
                username = "newuser",
                email = "newuser@example.com",
                displayName = "New User",
            )
        val result = eventGateway.process(createUserMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authenticated", (result as OperationResult.Error).message)
    }

    @Test
    fun `duplicate username returns error`() {
        val request =
            CreateUserRequest(
                username = "regularuser",
                email = "other@example.com",
                displayName = "Other User",
            )
        val result = eventGateway.process(createUserMessage(request, superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Username already exists", (result as OperationResult.Error).message)
    }

    // --- Text-based translate path tests ---

    @Test
    fun `text command creates user`() {
        val result =
            eventGateway.process(
                textMessage("create user newuser newuser@example.com NewUser", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("newuser", principal.username)
        assertEquals("NewUser", principal.displayName)
        assertEquals(Role.USER, principal.role)
    }

    @Test
    fun `text command creates user with role`() {
        val result =
            eventGateway.process(
                textMessage("create user newadmin newadmin@example.com NewAdmin admin", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals(Role.ADMIN, principal.role)
    }

    @Test
    fun `text command with hyphenated role normalizes correctly`() {
        val result =
            eventGateway.process(
                textMessage("create user newsa newsa@example.com NewSA super-admin", superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals(Role.SUPER_ADMIN, principal.role)
    }

    @Test
    fun `text command with invalid role defaults to USER`() {
        val result =
            eventGateway.process(
                textMessage(
                    "create user newuser2 newuser2@example.com NewUser bogusrole",
                    superAdmin,
                )
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals(Role.USER, principal.role)
    }

    @Test
    fun `text command with too few parts is not handled`() {
        val result =
            eventGateway.process(textMessage("create user newuser newuser@example.com", superAdmin))

        assertInstanceOf(OperationResult.NotHandled::class.java, result)
    }

    @Test
    fun `text command requires super admin`() {
        val result =
            eventGateway.process(
                textMessage("create user newuser newuser@example.com NewUser", regularUser)
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }
}
