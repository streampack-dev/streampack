/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.SuspendAccountRequest
import dev.streampack.blog.model.UnsuspendAccountRequest
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Integration tests for account unsuspension via the event system */
@SpringBootTest
@Transactional
class UnsuspendAccountOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var regularUser: UserPrincipal
    private lateinit var adminUser: UserPrincipal

    @BeforeEach
    fun setUp() {
        regularUser =
            userRegistrationService.register(
                username = "regularuser",
                email = "regular@example.com",
                displayName = "Regular User",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "regular@example.com",
            )
        adminUser =
            userRegistrationService.register(
                username = "adminuser",
                email = "admin@example.com",
                displayName = "Admin User",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "admin@example.com",
                role = Role.ADMIN,
            )
    }

    private fun message(payload: Any, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "admin/users/unsuspend",
                    user = asUser,
                ),
            )
            .build()

    @Test
    fun `admin can unsuspend a suspended user`() {
        eventGateway.process(message(SuspendAccountRequest("regularuser"), adminUser))

        val result =
            eventGateway.process(message(UnsuspendAccountRequest("regularuser"), adminUser))

        assertInstanceOf(OperationResult.Success::class.java, result)
        assertEquals("Account unsuspended", (result as OperationResult.Success).payload)

        val user = userRepository.findByUsername("regularuser")!!
        assertTrue(user.isActive())
    }

    @Test
    fun `cannot unsuspend an active user`() {
        val result =
            eventGateway.process(message(UnsuspendAccountRequest("regularuser"), adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User is not suspended", (result as OperationResult.Error).message)
    }

    @Test
    fun `non-admin cannot unsuspend`() {
        val result =
            eventGateway.process(message(UnsuspendAccountRequest("adminuser"), regularUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }

    @Test
    fun `cannot unsuspend nonexistent user`() {
        val result = eventGateway.process(message(UnsuspendAccountRequest("nobody"), adminUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("User not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val result = eventGateway.process(message(UnsuspendAccountRequest("regularuser"), null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Insufficient privileges: requires ADMIN",
            (result as OperationResult.Error).message,
        )
    }
}
