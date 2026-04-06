/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.model.SecretRef
import dev.streampack.core.service.SilentStartupException
import dev.streampack.irc.entity.IrcNetwork
import dev.streampack.irc.repository.IrcNetworkRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito
import org.springframework.core.env.Environment

class IrcSecretRefStartupGuardTests {
    private val repository = Mockito.mock(IrcNetworkRepository::class.java)
    private val environment = Mockito.mock(Environment::class.java)
    private val guard = IrcSecretRefStartupGuard(repository, environment, true)

    @Test
    fun `literal sasl credentials are externalized and startup fails`() {
        val network =
            IrcNetwork(
                name = "libera",
                host = "irc.libera.chat",
                nick = "nevet",
                saslAccount = SecretRef.literal("acct"),
                saslPassword = SecretRef.literal("pass"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(network))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }

        val captor = ArgumentCaptor.forClass(IrcNetwork::class.java)
        Mockito.verify(repository).save(captor.capture())
        assertEquals("env://IRC_LIBERA_SASL_ACCOUNT", captor.value.saslAccount?.asStoredValue())
        assertEquals("env://IRC_LIBERA_SASL_PASSWORD", captor.value.saslPassword?.asStoredValue())
    }

    @Test
    fun `missing environment variable for env ref fails startup`() {
        val network =
            IrcNetwork(
                name = "libera",
                host = "irc.libera.chat",
                nick = "nevet",
                saslPassword = SecretRef.env("IRC_LIBERA_SASL_PASSWORD"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(network))

        assertThrows(SilentStartupException::class.java) { guard.enforce { null } }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }

    @Test
    fun `env-backed credentials pass when environment values exist`() {
        val network =
            IrcNetwork(
                name = "libera",
                host = "irc.libera.chat",
                nick = "nevet",
                saslPassword = SecretRef.env("IRC_LIBERA_SASL_PASSWORD"),
            )
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(network))

        guard.enforce { key -> if (key == "IRC_LIBERA_SASL_PASSWORD") "secret" else null }
        Mockito.verify(repository, Mockito.never()).save(Mockito.any())
    }
}
