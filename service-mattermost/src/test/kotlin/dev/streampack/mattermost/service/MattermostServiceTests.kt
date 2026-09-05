/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.mattermost.repository.MattermostChannelRepository
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
                channel!!.provenanceUri()
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
                channel.provenanceUri()
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
                channel.provenanceUri()
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
                channel.provenanceUri()
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
}
