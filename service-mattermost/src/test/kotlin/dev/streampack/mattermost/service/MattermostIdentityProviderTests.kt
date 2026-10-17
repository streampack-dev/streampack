/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.service.IdentityResolution
import dev.streampack.mattermost.entity.MattermostServer
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider

class MattermostIdentityProviderTests {
    private val repository = Mockito.mock(MattermostServerRepository::class.java)
    private val manager = Mockito.mock(MattermostConnectionManager::class.java)
    private val adapter = Mockito.mock(MattermostAdapter::class.java)

    @Suppress("UNCHECKED_CAST")
    private val connections =
        Mockito.mock(ObjectProvider::class.java) as ObjectProvider<MattermostConnectionManager>
    private val provider = MattermostIdentityProvider(repository, connections)

    private val aliceId = "a1ice0000000000000000000ab"

    @BeforeEach
    fun setUp() {
        val work = MattermostServer(name = "work", baseUrl = "https://mm.example.com")
        Mockito.`when`(repository.findByNameAndDeletedFalse("work")).thenReturn(work)
        Mockito.`when`(repository.findByDeletedFalse()).thenReturn(listOf(work))
    }

    private fun connected() {
        Mockito.`when`(connections.ifAvailable).thenReturn(manager)
        Mockito.`when`(manager.getAdapter("work")).thenReturn(adapter)
        Mockito.`when`(adapter.isConnected()).thenReturn(true)
        Mockito.`when`(adapter.lookupUser("alice"))
            .thenReturn(MattermostUserView(id = aliceId, username = "alice"))
    }

    private fun valid(resolution: IdentityResolution): IdentityResolution.Valid =
        assertInstanceOf(IdentityResolution.Valid::class.java, resolution)

    private fun reason(resolution: IdentityResolution): String =
        assertInstanceOf(IdentityResolution.Invalid::class.java, resolution).reason

    @Test
    fun `a user id is used as it is, connected or not`() {
        val resolved = valid(provider.resolveIdentity("work", " $aliceId "))
        assertEquals("work", resolved.serviceId)
        assertEquals(aliceId, resolved.externalIdentifier)
    }

    @Test
    fun `a username is looked up and its id is linked`() {
        connected()
        assertEquals(aliceId, valid(provider.resolveIdentity("work", "alice")).externalIdentifier)
        assertEquals(aliceId, valid(provider.resolveIdentity("work", "@alice")).externalIdentifier)
    }

    @Test
    fun `an unknown username is refused`() {
        connected()
        assertEquals(
            "No Mattermost user 'nobody' on 'work'",
            reason(provider.resolveIdentity("work", "@nobody")),
        )
    }

    @Test
    fun `a username on a server that isn't connected is refused, not stored`() {
        assertEquals(
            "'work' is not connected; give the user id, or connect first",
            reason(provider.resolveIdentity("work", "alice")),
        )
        Mockito.`when`(connections.ifAvailable).thenReturn(manager)
        Mockito.`when`(manager.getAdapter("work")).thenReturn(adapter)
        Mockito.`when`(adapter.isConnected()).thenReturn(false)
        assertEquals(
            "'work' is not connected; give the user id, or connect first",
            reason(provider.resolveIdentity("work", "alice")),
        )
    }

    @Test
    fun `unknown servers and blank identities are refused`() {
        assertEquals(
            "Unknown Mattermost server: nowhere",
            reason(provider.resolveIdentity("nowhere", aliceId)),
        )
        assertEquals(
            "Mattermost user id cannot be blank",
            reason(provider.resolveIdentity("work", " ")),
        )
    }

    @Test
    fun `link help names both forms`() {
        val description = provider.describeIdentity()
        assertEquals(Protocol.MATTERMOST, description.protocol)
        assertEquals("username-or-user-id", description.externalIdLabel)
        assertEquals(listOf("work"), description.availableServices)
    }
}
