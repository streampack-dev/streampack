/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.slack.repository.SlackChannelRepository
import dev.streampack.slack.repository.SlackWorkspaceRepository
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
class SlackServiceTests {

    @Autowired lateinit var slackService: SlackService
    @Autowired lateinit var workspaceRepository: SlackWorkspaceRepository
    @Autowired lateinit var channelRepository: SlackChannelRepository
    @Autowired lateinit var channelControlOptionsRepository: ChannelControlOptionsRepository

    @Test
    fun `connect persists workspace entity`() {
        val result = slackService.connect("jvm-news", "xoxb-test-token", "xapp-test-token")
        assertTrue(result.contains("Connecting"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")
        assertNotNull(workspace)
        assertEquals("xoxb-test-token", workspace!!.botToken.asStoredValue())
        assertEquals("xapp-test-token", workspace.appToken.asStoredValue())
    }

    @Test
    fun `connect with credentials updates existing workspace`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        val result = slackService.connect("jvm-news", "xoxb-new", "xapp-new")
        assertTrue(result.contains("Connecting"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        assertEquals("xoxb-new", workspace.botToken.asStoredValue())
        assertEquals("xapp-new", workspace.appToken.asStoredValue())
    }

    @Test
    fun `connect without credentials uses stored data`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        slackService.disconnect("jvm-news")
        val result = slackService.connect("jvm-news")
        assertTrue(result.contains("Connecting"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        assertEquals("xoxb-test", workspace.botToken.asStoredValue())
    }

    @Test
    fun `connect without credentials for unknown workspace returns error`() {
        val result = slackService.connect("nonexistent")
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun `join persists channel entity and creates ChannelControlOptions`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        val result = slackService.join("jvm-news", "#java")
        assertTrue(result.contains("Joined"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        val channel = channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#java")
        assertNotNull(channel)

        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel!!.provenanceUri()
            )
        assertNotNull(options)
        assertFalse(options!!.autojoin)
    }

    @Test
    fun `join with unknown workspace returns error`() {
        val result = slackService.join("nonexistent", "#java")
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun `setAutojoin updates ChannelControlOptions`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        slackService.join("jvm-news", "#java")
        val result = slackService.setAutojoin("jvm-news", "#java", true)
        assertTrue(result.contains("true"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        val channel = channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#java")!!
        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel.provenanceUri()
            )
        assertTrue(options!!.autojoin)
    }

    @Test
    fun `setAutoconnect updates entity flag`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        val result = slackService.setAutoconnect("jvm-news", true)
        assertTrue(result.contains("true"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        assertTrue(workspace.autoconnect)
    }

    @Test
    fun `setAutomute updates ChannelControlOptions`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        slackService.join("jvm-news", "#java")
        val result = slackService.setAutomute("jvm-news", "#java", true)
        assertTrue(result.contains("true"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        val channel = channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#java")!!
        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel.provenanceUri()
            )
        assertTrue(options!!.automute)
    }

    @Test
    fun `setVisible updates ChannelControlOptions`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        slackService.join("jvm-news", "#java")
        val result = slackService.setVisible("jvm-news", "#java", false)
        assertTrue(result.contains("false"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        val channel = channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#java")!!
        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel.provenanceUri()
            )
        assertFalse(options!!.visible)
    }

    @Test
    fun `setLogged updates ChannelControlOptions`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        slackService.join("jvm-news", "#java")
        val result = slackService.setLogged("jvm-news", "#java", false)
        assertTrue(result.contains("false"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        val channel = channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#java")!!
        val options =
            channelControlOptionsRepository.findByProvenanceUriAndDeletedFalse(
                channel.provenanceUri()
            )
        assertFalse(options!!.logged)
    }

    @Test
    fun `setSignal updates workspace signal character`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        val result = slackService.setSignal("jvm-news", "~")
        assertTrue(result.contains("~"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        assertEquals("~", workspace.signalCharacter)
    }

    @Test
    fun `setSignal with null resets to global default`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        slackService.setSignal("jvm-news", "~")
        val result = slackService.setSignal("jvm-news", null)
        assertTrue(result.contains("reset"))

        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        assertNull(workspace.signalCharacter)
    }

    @Test
    fun `setSignal with unknown workspace returns error`() {
        val result = slackService.setSignal("nonexistent", "~")
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun `disconnect with unknown workspace returns error`() {
        val result = slackService.disconnect("nonexistent")
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun `remove soft-deletes workspace and channels`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        slackService.join("jvm-news", "#java")
        slackService.join("jvm-news", "#kotlin")

        val result = slackService.remove("jvm-news")
        assertTrue(result.contains("removed"))

        assertNull(workspaceRepository.findByNameAndDeletedFalse("jvm-news"))
        val workspace = workspaceRepository.findAll().first { it.name == "jvm-news" }
        assertTrue(workspace.deleted)

        val channels = channelRepository.findByWorkspaceAndDeletedFalse(workspace)
        assertTrue(channels.isEmpty())
    }

    @Test
    fun `remove with unknown workspace returns error`() {
        val result = slackService.remove("nonexistent")
        assertTrue(result.startsWith("Error:"))
    }

    @Test
    fun `connect after remove reuses name`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        slackService.remove("jvm-news")
        val result = slackService.connect("jvm-news", "xoxb-new", "xapp-new")
        assertTrue(result.contains("Connecting"))
    }

    @Test
    fun `status with no workspaces shows empty message`() {
        val result = slackService.status(null)
        assertEquals("No Slack workspaces configured", result)
    }

    @Test
    fun `status with workspaces shows summaries`() {
        slackService.connect("jvm-news", "xoxb-test", "xapp-test")
        val result = slackService.status(null)
        assertTrue(result.contains("jvm-news"))
    }
}
