/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.repository.ChannelControlOptionsRepository
import dev.streampack.core.service.ChannelControlService
import dev.streampack.slack.entity.SlackChannel
import dev.streampack.slack.repository.SlackChannelRepository
import dev.streampack.slack.repository.SlackWorkspaceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

/**
 * Channel settings are kept by the channel's Slack id, the address its messages arrive with, so
 * mute, logged and visible reach its traffic.
 */
@SpringBootTest
@Transactional
class SlackChannelIdTests {
    @Autowired lateinit var workspaceRepository: SlackWorkspaceRepository
    @Autowired lateinit var channelRepository: SlackChannelRepository
    @Autowired lateinit var channelControlService: ChannelControlService
    @Autowired lateinit var optionsRepository: ChannelControlOptionsRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    private val adapter = Mockito.mock(SlackAdapter::class.java)
    private val connectionManager = Mockito.mock(SlackConnectionManager::class.java)
    private lateinit var service: SlackService

    private val java = SlackConversationRef(id = "C0JAVA0001", isPrivate = false, name = "java")

    @BeforeEach
    fun connected() {
        val beans = DefaultListableBeanFactory()
        beans.registerSingleton("slackConnectionManager", connectionManager)
        service =
            SlackService(
                workspaceRepository,
                channelRepository,
                channelControlService,
                beans.getBeanProvider(SlackConnectionManager::class.java),
            )
        service.connect("jvm-news", "xoxb-test", "xapp-test")
        Mockito.`when`(connectionManager.getAdapter("jvm-news")).thenReturn(adapter)
    }

    private fun options(channelId: String) =
        optionsRepository.findByProvenanceUriAndDeletedFalse(
            Provenance(Protocol.SLACK, "jvm-news", replyTo = channelId).encode()
        )

    @Test
    fun `join by name finds the id, joins, and keys the settings by it`() {
        Mockito.`when`(adapter.resolveChannel("#java")).thenReturn(java)
        Mockito.`when`(adapter.joinChannel("C0JAVA0001")).thenReturn(true)

        assertEquals("Joined '#java' on 'jvm-news'", service.join("jvm-news", "#java"))

        val options = options("C0JAVA0001")!!
        assertTrue(options.visible)
        assertTrue(options.logged)
        Mockito.verify(adapter).joinChannel("C0JAVA0001")
    }

    @Test
    fun `settings set by name reach the address messages arrive with`() {
        Mockito.`when`(adapter.resolveChannel("#java")).thenReturn(java)
        Mockito.`when`(adapter.joinChannel("C0JAVA0001")).thenReturn(true)
        service.join("jvm-news", "#java")

        service.mute("jvm-news", "#java")
        service.setLogged("jvm-news", "java", false)

        // As SlackAdapter addresses a message in the channel
        val incoming = Provenance(Protocol.SLACK, "jvm-news", replyTo = "C0JAVA0001")
        assertTrue(channelControlService.getOptions(incoming.encode())!!.automute)
        assertFalse(channelControlService.isLogged(incoming))
    }

    @Test
    fun `a private channel is registered hidden and unlogged, and not joined`() {
        Mockito.`when`(adapter.resolveChannel("#secret"))
            .thenReturn(SlackConversationRef("G0SECRET01", isPrivate = true, name = "secret"))

        assertTrue(service.join("jvm-news", "#secret").contains("invite the bot"))

        val options = options("G0SECRET01")!!
        assertFalse(options.visible)
        assertFalse(options.logged)
        Mockito.verify(adapter, Mockito.never()).joinChannel(Mockito.anyString())
    }

    @Test
    fun `a channel Slack doesn't know is not registered`() {
        assertTrue(service.join("jvm-news", "#nowhere").startsWith("Error:"))
        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        assertTrue(channelRepository.findByWorkspaceAndDeletedFalse(workspace).isEmpty())
    }

    @Test
    fun `leave takes the bot out of the channel and keeps it registered`() {
        Mockito.`when`(adapter.resolveChannel("#java")).thenReturn(java)
        Mockito.`when`(adapter.joinChannel("C0JAVA0001")).thenReturn(true)
        Mockito.`when`(adapter.leaveChannel("C0JAVA0001")).thenReturn(true)
        service.join("jvm-news", "#java")

        assertEquals("Left '#java' on 'jvm-news'", service.leave("jvm-news", "#java"))
        Mockito.verify(adapter).leaveChannel("C0JAVA0001")
        assertTrue(service.mute("jvm-news", "#java").startsWith("Muted"))
    }

    @Test
    fun `not connected, join takes an id but not a name`() {
        Mockito.`when`(connectionManager.getAdapter("jvm-news")).thenReturn(null)

        assertTrue(service.join("jvm-news", "#java").contains("join by channel id"))
        assertTrue(service.join("jvm-news", "C0JAVA0001").startsWith("Registered"))
        assertTrue(options("C0JAVA0001") != null)
    }

    @Test
    fun `a channel registered by name before ids were known gets its id on join`() {
        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        channelRepository.save(SlackChannel(workspace = workspace, name = "#java"))
        assertTrue(service.mute("jvm-news", "#java").contains("no Slack id yet"))

        Mockito.`when`(adapter.resolveChannel("#java")).thenReturn(java)
        Mockito.`when`(adapter.joinChannel("C0JAVA0001")).thenReturn(true)
        service.join("jvm-news", "#java")

        assertEquals(1, channelRepository.findByWorkspaceAndDeletedFalse(workspace).size)
        assertTrue(service.mute("jvm-news", "#java").startsWith("Muted"))
        assertTrue(options("C0JAVA0001")!!.automute)
    }

    @Test
    fun `the migration moves settings kept by name onto the channel's id`() {
        val workspace = workspaceRepository.findByNameAndDeletedFalse("jvm-news")!!
        channelRepository.save(
            SlackChannel(workspace = workspace, name = "#java", channelId = "C0JAVA0001")
        )
        channelRepository.flush()
        channelControlService.setFlag("slack://jvm-news/%23java", "automute", true)
        optionsRepository.flush()

        jdbc.execute(
            ClassPathResource("db/migration/V54__slack_channel_options_by_id.sql")
                .getContentAsString(Charsets.UTF_8)
        )

        assertTrue(channelControlService.getOptions("slack://jvm-news/C0JAVA0001")!!.automute)
        assertEquals(null, channelControlService.getOptions("slack://jvm-news/%23java"))
    }
}
