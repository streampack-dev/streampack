/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.operation

import dev.streampack.core.TestChannelConfiguration
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.EditProfileRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.UserRegistrationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Integration tests for self-service profile editing via EditProfileOperation */
@SpringBootTest
@Transactional
@Import(TestChannelConfiguration::class)
class EditProfileOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var user: UserPrincipal

    @BeforeEach
    fun setUp() {
        user =
            userRegistrationService.register(
                username = "testuser",
                email = "test@example.com",
                displayName = "Test User",
                protocol = Protocol.HTTP,
                serviceId = "test-service",
                externalIdentifier = "testuser",
            )
    }

    private fun editProfileMessage(request: EditProfileRequest, asUser: UserPrincipal?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "test-service",
                    replyTo = "profile/edit",
                    user = asUser,
                ),
            )
            .build()

    @Test
    fun `authenticated user can change own displayName`() {
        val request = EditProfileRequest(displayName = "Updated Name")
        val result = eventGateway.process(editProfileMessage(request, user))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("Updated Name", principal.displayName)
    }

    @Test
    fun `authenticated user can change own email`() {
        val request = EditProfileRequest(email = "new@example.com")
        val result = eventGateway.process(editProfileMessage(request, user))

        assertInstanceOf(OperationResult.Success::class.java, result)

        val updated = userRepository.findByUsername("testuser")!!
        assertEquals("new@example.com", updated.email)
    }

    @Test
    fun `can change both at once`() {
        val request = EditProfileRequest(displayName = "New Name", email = "new@example.com")
        val result = eventGateway.process(editProfileMessage(request, user))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("New Name", principal.displayName)

        val updated = userRepository.findByUsername("testuser")!!
        assertEquals("new@example.com", updated.email)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val request = EditProfileRequest(displayName = "Hacked")
        val result = eventGateway.process(editProfileMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authenticated", (result as OperationResult.Error).message)
    }

    @Test
    fun `null fields are not applied`() {
        val request = EditProfileRequest()
        val result = eventGateway.process(editProfileMessage(request, user))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val principal = (result as OperationResult.Success).payload as UserPrincipal
        assertEquals("Test User", principal.displayName)

        val updated = userRepository.findByUsername("testuser")!!
        assertEquals("test@example.com", updated.email)
    }
}
