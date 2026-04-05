/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import com.enigmastation.streampack.core.entity.ServiceBinding
import com.enigmastation.streampack.core.entity.User
import com.enigmastation.streampack.core.model.Protocol
import com.enigmastation.streampack.core.model.Role
import com.enigmastation.streampack.core.model.UserStatus
import com.enigmastation.streampack.core.repository.ServiceBindingRepository
import com.enigmastation.streampack.core.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class UserResolutionServiceTests {

    @Autowired lateinit var userResolutionService: UserResolutionService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var serviceBindingRepository: ServiceBindingRepository

    @Test
    fun `resolves existing user by protocol binding`() {
        val user =
            userRepository.saveAndFlush(
                User(
                    username = "testuser",
                    email = "testuser@example.com",
                    displayName = "Test User",
                )
            )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user,
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "~testuser@example.com/testuser",
            )
        )

        val principal =
            userResolutionService.resolve(
                Protocol.IRC,
                "ircservice",
                "~testuser@example.com/testuser",
            )

        assertNotNull(principal)
        assertEquals("testuser", principal!!.username)
        assertEquals("Test User", principal.displayName)
        assertEquals(Role.USER, principal.role)
        assertEquals(user.id, principal.id)
    }

    @Test
    fun `returns null when no binding exists`() {
        val principal = userResolutionService.resolve(Protocol.DISCORD, "nonexistent", "nobody")

        assertNull(principal)
    }

    @Test
    fun `returns null for erased user`() {
        val user =
            userRepository.saveAndFlush(
                User(
                    username = "erased-user",
                    email = "erased@test.com",
                    displayName = "Erased User",
                    status = UserStatus.ERASED,
                )
            )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user,
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "ghost",
            )
        )

        val principal = userResolutionService.resolve(Protocol.IRC, "ircservice", "ghost")

        assertNull(principal)
    }

    @Test
    fun `resolves correct user when multiple bindings exist`() {
        val user1 =
            userRepository.saveAndFlush(
                User(username = "alice", email = "alice@test.com", displayName = "Alice")
            )
        val user2 =
            userRepository.saveAndFlush(
                User(username = "bob", email = "bob@test.com", displayName = "Bob")
            )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user1,
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "~alice@example.com/alice",
            )
        )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user2,
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "~bob@example.com/bob",
            )
        )

        val principal =
            userResolutionService.resolve(Protocol.IRC, "ircservice", "~bob@example.com/bob")

        assertNotNull(principal)
        assertEquals("bob", principal!!.username)
        assertEquals(user2.id, principal.id)
    }

    @Test
    fun `same user resolves across different protocols`() {
        val user =
            userRepository.saveAndFlush(
                User(
                    username = "testuser",
                    email = "testuser@example.com",
                    displayName = "Test User",
                )
            )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user,
                protocol = Protocol.IRC,
                serviceId = "ircservice",
                externalIdentifier = "~testuser@example.com/testuser",
            )
        )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user,
                protocol = Protocol.DISCORD,
                serviceId = "jvm-community",
                externalIdentifier = "testuser#1234",
            )
        )

        val fromIrc =
            userResolutionService.resolve(
                Protocol.IRC,
                "ircservice",
                "~testuser@example.com/testuser",
            )
        val fromDiscord =
            userResolutionService.resolve(Protocol.DISCORD, "jvm-community", "testuser#1234")

        assertNotNull(fromIrc)
        assertNotNull(fromDiscord)
        assertEquals(fromIrc!!.id, fromDiscord!!.id)
    }
}
