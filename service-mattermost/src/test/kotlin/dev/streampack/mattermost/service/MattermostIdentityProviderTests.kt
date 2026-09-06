/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.service.IdentityResolution
import dev.streampack.mattermost.entity.MattermostServer
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class MattermostIdentityProviderTests {
    private val repository = Mockito.mock(MattermostServerRepository::class.java)
    private val provider = MattermostIdentityProvider(repository)

    @Test
    fun `identities resolve against registered servers by user id`() {
        val work = MattermostServer(name = "work", baseUrl = "https://mm.example.com")
        Mockito.`when`(repository.findByNameAndDeletedFalse("work")).thenReturn(work)
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(work))

        val valid =
            assertInstanceOf(
                IdentityResolution.Valid::class.java,
                provider.resolveIdentity("work", "alice01"),
            )
        assertEquals("work", valid.serviceId)
        assertEquals("alice01", valid.externalIdentifier)
        assertInstanceOf(
            IdentityResolution.Invalid::class.java,
            provider.resolveIdentity("nowhere", "alice01"),
        )
        assertInstanceOf(
            IdentityResolution.Invalid::class.java,
            provider.resolveIdentity("work", " "),
        )

        val description = provider.describeIdentity()
        assertEquals(Protocol.MATTERMOST, description.protocol)
        assertEquals(listOf("work"), description.availableServices)
    }
}
