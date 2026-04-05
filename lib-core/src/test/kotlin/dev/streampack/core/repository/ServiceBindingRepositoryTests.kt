/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.repository

import com.enigmastation.streampack.core.entity.ServiceBinding
import com.enigmastation.streampack.core.entity.User
import com.enigmastation.streampack.core.model.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class ServiceBindingRepositoryTests {

    @Autowired lateinit var serviceBindingRepository: ServiceBindingRepository
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        testUser =
            userRepository.save(
                User(
                    username = "testuser",
                    email = "testuser@example.com",
                    displayName = "Test User",
                )
            )
    }

    @Test
    fun `save and retrieve service binding`() {
        val binding =
            serviceBindingRepository.save(
                ServiceBinding(
                    user = testUser,
                    protocol = Protocol.IRC,
                    serviceId = "ircservice",
                    externalIdentifier = "~testuser@about/java/testuser",
                    metadata = mapOf("network" to "libera"),
                )
            )
        val found = serviceBindingRepository.findById(binding.id).orElse(null)

        assertNotNull(found)
        assertEquals(Protocol.IRC, found.protocol)
        assertEquals("ircservice", found.serviceId)
        assertEquals("~testuser@about/java/testuser", found.externalIdentifier)
    }

    @Test
    fun `resolve by protocol, serviceId, and externalIdentifier`() {
        serviceBindingRepository.save(
            ServiceBinding(
                user = testUser,
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "~testuser@about/java/testuser",
            )
        )
        val found =
            serviceBindingRepository.resolve(
                Protocol.IRC,
                "ircservice",
                "~testuser@about/java/testuser",
            )

        assertNotNull(found)
        assertEquals(testUser.id, found!!.user.id)
    }

    @Test
    fun `resolution returns null for no match`() {
        val found = serviceBindingRepository.resolve(Protocol.DISCORD, "nonexistent", "nobody")
        assertNull(found)
    }

    @Test
    fun `unique constraint on protocol, serviceId, externalIdentifier`() {
        serviceBindingRepository.save(
            ServiceBinding(
                user = testUser,
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "~testuser@about/java/testuser",
            )
        )
        serviceBindingRepository.flush()

        val otherUser =
            userRepository.save(
                User(username = "otheruser", email = "other@test.com", displayName = "Other User")
            )

        assertThrows(Exception::class.java) {
            serviceBindingRepository.save(
                ServiceBinding(
                    user = otherUser,
                    protocol = Protocol.IRC,
                    serviceId = "ircservice",
                    externalIdentifier = "~testuser@about/java/testuser",
                )
            )
            serviceBindingRepository.flush()
        }
    }

    @Test
    fun `JSONB metadata round-trip`() {
        val metadata = mapOf("authMethod" to "oidc", "oauthProvider" to "github")
        serviceBindingRepository.save(
            ServiceBinding(
                user = testUser,
                protocol = Protocol.HTTP,
                serviceId = "blog-service",
                externalIdentifier = "testuser",
                metadata = metadata,
            )
        )
        serviceBindingRepository.flush()

        val found = serviceBindingRepository.resolve(Protocol.HTTP, "blog-service", "testuser")

        assertNotNull(found)
        assertEquals("oidc", found!!.metadata["authMethod"])
        assertEquals("github", found.metadata["oauthProvider"])
    }

    @Test
    fun `multiple bindings per user across protocols`() {
        serviceBindingRepository.save(
            ServiceBinding(
                user = testUser,
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "~testuser@about/java/testuser",
            )
        )
        serviceBindingRepository.save(
            ServiceBinding(
                user = testUser,
                protocol = Protocol.DISCORD,
                serviceId = "jvm-community",
                externalIdentifier = "testuser#1234",
            )
        )

        val ircBinding =
            serviceBindingRepository.resolve(
                Protocol.IRC,
                "ircservice",
                "~testuser@about/java/testuser",
            )
        val discordBinding =
            serviceBindingRepository.resolve(Protocol.DISCORD, "jvm-community", "testuser#1234")

        assertNotNull(ircBinding)
        assertNotNull(discordBinding)
        assertEquals(testUser.id, ircBinding!!.user.id)
        assertEquals(testUser.id, discordBinding!!.user.id)
    }
}
