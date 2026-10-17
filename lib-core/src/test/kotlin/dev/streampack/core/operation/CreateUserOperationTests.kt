/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.CreateUserRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
                textMessage("create user newuser newuser@example.com NewUser", superAdmin),
            )

        assertEquals(OperationResult.Success("Created newuser (NewUser), user."), result)
        val user = userRepository.findByUsername("newuser")!!
        assertEquals("NewUser", user.displayName)
        assertEquals(Role.USER, user.role)
    }

    @Test
    fun `text command creates user with role`() {
        val result =
            eventGateway.process(
                textMessage("create user newadmin newadmin@example.com NewAdmin admin", superAdmin),
            )

        assertEquals(OperationResult.Success("Created newadmin (NewAdmin), admin."), result)
        assertEquals(Role.ADMIN, userRepository.findByUsername("newadmin")!!.role)
    }

    @Test
    fun `text command with hyphenated role normalizes correctly`() {
        val result =
            eventGateway.process(
                textMessage("create user newsa newsa@example.com NewSA super-admin", superAdmin),
            )

        assertEquals(OperationResult.Success("Created newsa (NewSA), super_admin."), result)
        assertEquals(Role.SUPER_ADMIN, userRepository.findByUsername("newsa")!!.role)
    }

    @Test
    fun `text command with an unknown role is refused, not made a user`() {
        val result =
            eventGateway.process(
                textMessage(
                    "create user newuser2 newuser2@example.com NewUser bogusrole",
                    superAdmin,
                ),
            )

        assertEquals(
            OperationResult.Error(
                "Unknown role 'bogusrole': use guest, user, admin, super_admin",
            ),
            result,
        )
        assertNull(userRepository.findByUsername("newuser2"))
    }

    @Test
    fun `text command takes a quoted display name`() {
        val result =
            eventGateway.process(
                textMessage("""create user dana dana@example.com "Dana Kim" admin""", superAdmin),
            )

        assertEquals(OperationResult.Success("Created dana (Dana Kim), admin."), result)
        val user = userRepository.findByUsername("dana")!!
        assertEquals("Dana Kim", user.displayName)
        assertEquals(Role.ADMIN, user.role)
    }

    @Test
    fun `an unquoted display name with spaces is refused, not split`() {
        val result =
            eventGateway.process(
                textMessage("create user dana dana@example.com Dana Kim admin", superAdmin),
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue(
            (result as OperationResult.Error).message.startsWith("Too many arguments"),
            result.message,
        )
        assertNull(userRepository.findByUsername("dana"))
    }

    @Test
    fun `text command with too few parts says how to use it`() {
        val result =
            eventGateway.process(textMessage("create user newuser newuser@example.com", superAdmin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertTrue((result as OperationResult.Error).message.startsWith("Usage: create user"))
    }

    @Test
    fun `a malformed command from someone not allowed only says so`() {
        val result = eventGateway.process(textMessage("create user newuser", regularUser))

        assertEquals(OperationResult.Error("Insufficient privileges"), result)
    }

    @Test
    fun `text command requires super admin`() {
        val result =
            eventGateway.process(
                textMessage("create user newuser newuser@example.com NewUser", regularUser),
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges", (result as OperationResult.Error).message)
    }
}
