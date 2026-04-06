/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Role
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class UserRegistrationServiceTests {

    @Autowired lateinit var userRegistrationService: UserRegistrationService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var serviceBindingRepository: ServiceBindingRepository

    @Test
    fun `register creates user with USER role and initial binding`() {
        val principal =
            userRegistrationService.register(
                username = "testuser",
                email = "testuser@example.com",
                displayName = "Test User",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "testuser",
                metadata = mapOf("authMethod" to "otp"),
            )

        assertEquals("testuser", principal.username)
        assertEquals("Test User", principal.displayName)
        assertEquals(Role.USER, principal.role)

        val user = userRepository.findByUsername("testuser")
        assertNotNull(user)
        assertEquals("testuser@example.com", user!!.email)

        val binding = serviceBindingRepository.resolve(Protocol.HTTP, "blog-service", "testuser")
        assertNotNull(binding)
        assertEquals(user.id, binding!!.user.id)
        assertEquals("otp", binding.metadata["authMethod"])
    }

    @Test
    fun `registerGuest creates user with GUEST role`() {
        val principal =
            userRegistrationService.registerGuest(
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "testuser",
            )

        assertEquals(Role.GUEST, principal.role)

        val binding = serviceBindingRepository.resolve(Protocol.IRC, "ircservice", "testuser")
        assertNotNull(binding)
    }

    @Test
    fun `registerGuest uses externalIdentifier as username and displayName`() {
        val principal =
            userRegistrationService.registerGuest(
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "somenick",
            )

        assertEquals("somenick", principal.username)
        assertEquals("somenick", principal.displayName)
    }

    @Test
    fun `linkProtocol adds binding to existing user`() {
        val principal =
            userRegistrationService.register(
                username = "testuser",
                email = "testuser@example.com",
                displayName = "Test User",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "testuser",
            )

        userRegistrationService.linkProtocol(
            userId = principal.id,
            protocol = Protocol.IRC,
            serviceId = "ircservice",
            externalIdentifier = "testuser",
        )

        val binding = serviceBindingRepository.resolve(Protocol.IRC, "ircservice", "testuser")
        assertNotNull(binding)
        assertEquals(principal.id, binding!!.user.id)
    }

    @Test
    fun `linkProtocol supports metadata`() {
        val principal =
            userRegistrationService.register(
                username = "testuser",
                email = "testuser@example.com",
                displayName = "Test User",
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "testuser",
            )

        userRegistrationService.linkProtocol(
            userId = principal.id,
            protocol = Protocol.DISCORD,
            serviceId = "jvm-community",
            externalIdentifier = "testuser#1234",
            metadata = mapOf("oauthToken" to "tok_abc"),
        )

        val binding =
            serviceBindingRepository.resolve(Protocol.DISCORD, "jvm-community", "testuser#1234")
        assertNotNull(binding)
        assertEquals("tok_abc", binding!!.metadata["oauthToken"])
    }

    @Test
    fun `linkProtocol throws for nonexistent user`() {
        assertThrows(IllegalArgumentException::class.java) {
            userRegistrationService.linkProtocol(
                userId = UUID.randomUUID(),
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "nobody",
            )
        }
    }

    @Test
    fun `register with duplicate username throws`() {
        userRegistrationService.register(
            username = "testuser",
            email = "testuser@example.com",
            displayName = "Test User",
            protocol = Protocol.HTTP,
            serviceId = "blog-service",
            externalIdentifier = "testuser",
        )

        assertThrows(Exception::class.java) {
            userRegistrationService.register(
                username = "testuser",
                email = "other@gmail.com",
                displayName = "Other Person",
                protocol = Protocol.DISCORD,
                serviceId = "jvm-community",
                externalIdentifier = "other#1234",
            )
        }
    }

    @Test
    fun `createUser creates user with emailVerified true and no binding`() {
        val principal =
            userRegistrationService.createUser(
                username = "provisioned",
                email = "provisioned@example.com",
                displayName = "Provisioned User",
            )

        assertEquals("provisioned", principal.username)
        assertEquals(Role.USER, principal.role)

        val user = userRepository.findByUsername("provisioned")
        assertNotNull(user)
        assertEquals(true, user!!.emailVerified)

        val bindings = serviceBindingRepository.findAll().filter { it.user.id == user.id }
        assertEquals(0, bindings.size)
    }

    @Test
    fun `createUser defaults to USER role`() {
        val principal =
            userRegistrationService.createUser(
                username = "defaultrole",
                email = "default@example.com",
                displayName = "Default Role",
            )

        assertEquals(Role.USER, principal.role)
    }

    @Test
    fun `createUser with duplicate username throws`() {
        userRegistrationService.createUser(
            username = "taken",
            email = "first@example.com",
            displayName = "First",
        )

        assertThrows(Exception::class.java) {
            userRegistrationService.createUser(
                username = "taken",
                email = "second@example.com",
                displayName = "Second",
            )
        }
    }

    @Test
    fun `registerGuest with duplicate externalIdentifier as username throws`() {
        userRegistrationService.registerGuest(
            protocol = Protocol.IRC,
            serviceId = "ircservice",
            externalIdentifier = "testuser",
        )

        assertThrows(Exception::class.java) {
            userRegistrationService.registerGuest(
                protocol = Protocol.DISCORD,
                serviceId = "jvm-community",
                externalIdentifier = "testuser",
            )
        }
    }
}
