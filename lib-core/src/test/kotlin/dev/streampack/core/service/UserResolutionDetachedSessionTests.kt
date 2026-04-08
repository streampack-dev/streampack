/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.entity.ServiceBinding
import dev.streampack.core.entity.User
import dev.streampack.core.model.Protocol
import dev.streampack.core.repository.ServiceBindingRepository
import dev.streampack.core.repository.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class UserResolutionDetachedSessionTests {

    @Autowired lateinit var userResolutionService: UserResolutionService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var serviceBindingRepository: ServiceBindingRepository

    @AfterEach
    fun cleanup() {
        serviceBindingRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Test
    fun `resolves service binding user outside test transaction`() {
        val user =
            userRepository.saveAndFlush(
                User(
                    username = "irc-detached-user",
                    email = "irc-detached-user@example.com",
                    displayName = "IRC Detached User",
                )
            )
        serviceBindingRepository.saveAndFlush(
            ServiceBinding(
                user = user,
                protocol = Protocol.IRC,
                serviceId = "libera",
                externalIdentifier = "detached@example.com",
            )
        )

        val principal =
            userResolutionService.resolve(Protocol.IRC, "libera", "detached@example.com")

        assertNotNull(principal)
        assertEquals("irc-detached-user", principal!!.username)
        assertEquals("IRC Detached User", principal.displayName)
    }
}
