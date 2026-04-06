/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.service

import dev.streampack.core.service.IdentityResolution
import dev.streampack.irc.entity.IrcNetwork
import dev.streampack.irc.repository.IrcNetworkRepository
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class IrcIdentityProviderTests {

    @Autowired lateinit var identityProvider: IrcIdentityProvider
    @Autowired lateinit var networkRepository: IrcNetworkRepository

    @BeforeEach
    fun setUp() {
        networkRepository.saveAndFlush(
            IrcNetwork(name = "libera", host = "irc.libera.chat", nick = "nevet")
        )
    }

    @Test
    fun `valid hostmask is accepted`() {
        val result = identityProvider.resolveIdentity("libera", "~testuser@example.com/testuser")
        assertInstanceOf(IdentityResolution.Valid::class.java, result)
    }

    @Test
    fun `blank identifier is rejected`() {
        val result = identityProvider.resolveIdentity("libera", "  ")
        assertInstanceOf(IdentityResolution.Invalid::class.java, result)
    }

    @Test
    fun `nick-only identifier is rejected`() {
        val result = identityProvider.resolveIdentity("libera", "testuser")
        assertInstanceOf(IdentityResolution.Invalid::class.java, result)
        assertTrue((result as IdentityResolution.Invalid).reason.contains("ident@host"))
    }

    @Test
    fun `missing host part is rejected`() {
        val result = identityProvider.resolveIdentity("libera", "testuser@")
        assertInstanceOf(IdentityResolution.Invalid::class.java, result)
        assertTrue((result as IdentityResolution.Invalid).reason.contains("host"))
    }

    @Test
    fun `missing ident part is rejected`() {
        val result = identityProvider.resolveIdentity("libera", "@example.com/testuser")
        assertInstanceOf(IdentityResolution.Invalid::class.java, result)
        assertTrue((result as IdentityResolution.Invalid).reason.contains("ident"))
    }

    @Test
    fun `unknown network is rejected`() {
        val result =
            identityProvider.resolveIdentity("nonexistent", "~testuser@example.com/testuser")
        assertInstanceOf(IdentityResolution.Invalid::class.java, result)
        assertTrue((result as IdentityResolution.Invalid).reason.contains("Unknown"))
    }

    @Test
    fun `describeIdentity shows hostmask label`() {
        val description = identityProvider.describeIdentity()
        assertTrue(description.externalIdLabel == "hostmask")
    }
}
