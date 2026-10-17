/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.core.service.ChannelControlService
import dev.streampack.mattermost.model.MattermostChannelRef
import dev.streampack.mattermost.repository.MattermostChannelRepository
import dev.streampack.mattermost.repository.MattermostServerRepository
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class MattermostServiceTests {

    @Autowired lateinit var mattermostService: MattermostService
    @Autowired lateinit var serverRepository: MattermostServerRepository
    @Autowired lateinit var channelRepository: MattermostChannelRepository
    @Autowired lateinit var channelControlOptionsRepository: ChannelControlOptionsRepository
    @Autowired lateinit var channelControlService: ChannelControlService

    /** The service as it is with a live connection to 'work', served by [adapter] */
    private fun connectedService(adapter: MattermostAdapter): MattermostService {
        val manager = Mockito.mock(MattermostConnectionManager::class.java)
        Mockito.`when`(manager.getAdapter("work")).thenReturn(adapter)
        @Suppress("UNCHECKED_CAST")
        val provider =
            Mockito.mock(ObjectProvider::class.java) as ObjectProvider<MattermostConnectionManager>
        Mockito.`when`(provider.ifAvailable).thenReturn(manager)
        Mockito.doAnswer {
                (it.arguments[0] as Consumer<MattermostConnectionManager>).accept(manager)
            }
            .`when`(provider)
            .ifAvailable(Mockito.any())
        return MattermostService(
            serverRepository,
            channelRepository,
            channelControlService,
            provider,
        )
    }

    @Test
    fun `connect persists server entity`() {
        val result =
            mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        assertTrue(result.contains("Connecting"))

        val server = serverRepository.findByNameAndDeletedFalse("work")
        assertNotNull(server)
        assertEquals("https://mattermost.example.com", server!!.baseUrl)
        assertEquals("token-123", server.token.asStoredValue())
    }

    @Test
    fun `connect without credentials for unknown server returns error`() {
        val result = mattermostService.connect("unknown")
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun `join persists channel entity and creates ChannelControlOptions for explicit channel id`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        val result = mattermostService.join("work", "abc123channelid00000000000")
        assertTrue(result.contains("Joined"))

        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        val channel =
            channelRepository.findByServerAndChannelIdAndDeletedFalse(
                server,
                "abc123channelid00000000000",
            )
        assertNotNull(channel)

        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel!!.provenanceUri(),
            )
        assertNotNull(options)
        assertFalse(options!!.autojoin)
    }

    @Test
    fun `setAutoconnect updates entity flag`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        val result = mattermostService.setAutoconnect("work", true)
        assertTrue(result.contains("true"))

        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        assertTrue(server.autoconnect)
    }

    @Test
    fun `setAutomute updates ChannelControlOptions`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        mattermostService.join("work", "abc123channelid00000000000")
        val result = mattermostService.setAutomute("work", "abc123channelid00000000000", true)
        assertTrue(result.contains("true"))

        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        val channel =
            channelRepository.findByServerAndChannelIdAndDeletedFalse(
                server,
                "abc123channelid00000000000",
            )!!
        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel.provenanceUri(),
            )
        assertTrue(options!!.automute)
    }

    @Test
    fun `setVisible updates ChannelControlOptions`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        mattermostService.join("work", "abc123channelid00000000000")
        val result = mattermostService.setVisible("work", "abc123channelid00000000000", false)
        assertTrue(result.contains("false"))

        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        val channel =
            channelRepository.findByServerAndChannelIdAndDeletedFalse(
                server,
                "abc123channelid00000000000",
            )!!
        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel.provenanceUri(),
            )
        assertFalse(options!!.visible)
    }

    @Test
    fun `setLogged updates ChannelControlOptions`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        mattermostService.join("work", "abc123channelid00000000000")
        val result = mattermostService.setLogged("work", "abc123channelid00000000000", false)
        assertTrue(result.contains("false"))

        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        val channel =
            channelRepository.findByServerAndChannelIdAndDeletedFalse(
                server,
                "abc123channelid00000000000",
            )!!
        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel.provenanceUri(),
            )
        assertFalse(options!!.logged)
    }

    @Test
    fun `setSignal updates server signal character`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        val result = mattermostService.setSignal("work", "~")
        assertTrue(result.contains("~"))

        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        assertEquals("~", server.signalCharacter)
    }

    @Test
    fun `remove soft-deletes server and channels`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        mattermostService.join("work", "abc123channelid00000000000")
        val result = mattermostService.remove("work")
        assertTrue(result.contains("removed"))

        assertNull(serverRepository.findByNameAndDeletedFalse("work"))
    }

    @Test
    fun `status with no servers shows empty message`() {
        assertEquals("No Mattermost servers configured", mattermostService.status(null))
    }

    @Test
    fun `connect accepts an env reference and stores it as a reference`() {
        mattermostService.connect(
            "work",
            "https://mattermost.example.com",
            "env://MATTERMOST_WORK_TOKEN",
        )
        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        assertTrue(server.token.isEnvRef())
        assertEquals("MATTERMOST_WORK_TOKEN", server.token.envKeyOrNull())
    }

    @Test
    fun `a removed server can be registered again and keeps its identity`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        val original = serverRepository.findByNameAndDeletedFalse("work")!!
        mattermostService.remove("work")
        assertNull(serverRepository.findByNameAndDeletedFalse("work"))

        val result = mattermostService.connect("work", "https://mm2.example.com", "token-456")
        /* Flush so the unique name constraint is actually checked inside the test transaction */
        serverRepository.flush()
        assertTrue(result.contains("Connecting"), result)
        val restored = serverRepository.findByNameAndDeletedFalse("work")!!
        assertEquals(original.id, restored.id)
        assertEquals("https://mm2.example.com", restored.baseUrl)
        assertEquals("token-456", restored.token.asStoredValue())
    }

    @Test
    fun `private and direct channels register hidden and unlogged while public ones stay visible`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        mattermostService.registerChannel(
            server,
            MattermostChannelRef(
                id = "priv0000000000000000000000",
                name = "secret-plans",
                teamId = "t1",
                type = "P",
            ),
        )
        mattermostService.registerChannel(
            server,
            MattermostChannelRef(
                id = "dm000000000000000000000000",
                name = "alice__bot",
                type = "D",
            ),
        )
        mattermostService.registerChannel(
            server,
            MattermostChannelRef(
                id = "pub00000000000000000000000",
                name = "town-square",
                teamId = "t1",
                type = "O",
            ),
        )

        fun options(id: String) =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channelRepository
                    .findByServerAndChannelIdAndDeletedFalse(server, id)!!
                    .provenanceUri(),
            )!!
        assertFalse(options("priv0000000000000000000000").visible)
        assertFalse(options("priv0000000000000000000000").logged)
        assertFalse(options("dm000000000000000000000000").visible)
        assertTrue(options("pub00000000000000000000000").visible)
        assertTrue(options("pub00000000000000000000000").logged)
    }

    @Test
    fun `same-named channels on two teams keep separate records and names must be disambiguated`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        mattermostService.registerChannel(
            server,
            MattermostChannelRef(
                id = "teama00000000000000000000",
                name = "town-square",
                teamId = "ta",
                teamName = "Team A",
                type = "O",
            ),
        )
        mattermostService.registerChannel(
            server,
            MattermostChannelRef(
                id = "teamb00000000000000000000",
                name = "town-square",
                teamId = "tb",
                teamName = "Team B",
                type = "O",
            ),
        )
        assertEquals(2, channelRepository.findByServerAndDeletedFalse(server).size)

        val byName = mattermostService.mute("work", "town-square")
        assertTrue(
            byName.startsWith("Error:") && byName.contains("teama00000000000000000000"),
            byName,
        )
        val byId = mattermostService.mute("work", "teamb00000000000000000000")
        assertTrue(byId.startsWith("Muted"), byId)
    }

    @Test
    fun `a joined channel is found by its display name while connected`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        val server = serverRepository.findByNameAndDeletedFalse("work")!!
        val devOps =
            MattermostChannelRef(
                id = "devops00000000000000000000",
                name = "dev-ops",
                displayName = "Dev Ops",
                teamId = "ta",
                teamName = "Team A",
                type = "O",
            )
        mattermostService.registerChannel(server, devOps)
        val adapter = Mockito.mock(MattermostAdapter::class.java)
        Mockito.`when`(adapter.resolveChannel("Dev Ops")).thenReturn(devOps)

        val result = connectedService(adapter).mute("work", "Dev Ops")
        assertTrue(result.startsWith("Muted"), result)
    }

    @Test
    fun `channels and join pass on why a lookup can't work`() {
        mattermostService.connect("work", "https://mattermost.example.com", "token-123")
        val adapter = Mockito.mock(MattermostAdapter::class.java)
        val noTeams = MattermostLookupException("'haadam' is not a member of any team on 'work'")
        Mockito.`when`(adapter.listChannels(null)).thenThrow(noTeams)
        Mockito.`when`(adapter.resolveChannel("lantern")).thenThrow(noTeams)
        val service = connectedService(adapter)

        assertEquals("Error: ${noTeams.message}", service.listChannels("work", null))
        assertEquals("Error: ${noTeams.message}", service.join("work", "lantern"))
    }
}
