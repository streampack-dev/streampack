/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.repository

import dev.streampack.core.model.SecretRef
import dev.streampack.slack.entity.SlackChannel
import dev.streampack.slack.entity.SlackWorkspace
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class SlackRepositoryTests {

    @Autowired lateinit var workspaceRepository: SlackWorkspaceRepository
    @Autowired lateinit var channelRepository: SlackChannelRepository

    @Test
    fun `save and retrieve workspace`() {
        val workspace =
            SlackWorkspace(
                name = "jvm-news",
                botToken = SecretRef.literal("xoxb-test-token"),
                appToken = SecretRef.literal("xapp-test-token"),
            )
        val saved = workspaceRepository.save(workspace)

        assertNotEquals(UUID(0, 0), saved.id)
        assertEquals(7, saved.id.version())

        val found = workspaceRepository.findByNameAndDeletedFalse("jvm-news")
        assertNotNull(found)
        assertEquals("jvm-news", found!!.name)
        assertEquals("xoxb-test-token", found.botToken.asStoredValue())
        assertEquals("xapp-test-token", found.appToken.asStoredValue())
    }

    @Test
    fun `save channel with workspace FK`() {
        val workspace =
            workspaceRepository.save(
                SlackWorkspace(
                    name = "jvm-news-ch",
                    botToken = SecretRef.literal("xoxb-test"),
                    appToken = SecretRef.literal("xapp-test"),
                )
            )
        val channel = channelRepository.save(SlackChannel(workspace = workspace, name = "#java"))

        assertNotEquals(UUID(0, 0), channel.id)
        val found = channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#java")
        assertNotNull(found)
        assertEquals("#java", found!!.name)
    }

    @Test
    fun `save channel with channelId`() {
        val workspace =
            workspaceRepository.save(
                SlackWorkspace(
                    name = "jvm-news-cid",
                    botToken = SecretRef.literal("xoxb-test"),
                    appToken = SecretRef.literal("xapp-test"),
                )
            )
        val channel =
            channelRepository.save(
                SlackChannel(workspace = workspace, name = "#java", channelId = "C0123456789")
            )

        val found = channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#java")
        assertNotNull(found)
        assertEquals("C0123456789", found!!.channelId)
    }

    @Test
    fun `findByAutoconnectTrue returns only autoconnect workspaces`() {
        workspaceRepository.save(
            SlackWorkspace(
                name = "auto-ws",
                botToken = SecretRef.literal("xoxb-test"),
                appToken = SecretRef.literal("xapp-test"),
                autoconnect = true,
            )
        )
        workspaceRepository.save(
            SlackWorkspace(
                name = "manual-ws",
                botToken = SecretRef.literal("xoxb-test2"),
                appToken = SecretRef.literal("xapp-test2"),
                autoconnect = false,
            )
        )

        val autoWorkspaces = workspaceRepository.findByAutoconnectTrueAndDeletedFalse()
        assertEquals(1, autoWorkspaces.size)
        assertEquals("auto-ws", autoWorkspaces[0].name)
    }

    @Test
    fun `soft delete excludes workspace from queries`() {
        workspaceRepository.save(
            SlackWorkspace(
                name = "active-ws",
                botToken = SecretRef.literal("xoxb-test"),
                appToken = SecretRef.literal("xapp-test"),
            )
        )
        workspaceRepository.save(
            SlackWorkspace(
                name = "deleted-ws",
                botToken = SecretRef.literal("xoxb-test2"),
                appToken = SecretRef.literal("xapp-test2"),
                deleted = true,
            )
        )

        val active = workspaceRepository.findByDeletedFalse()
        assertEquals(1, active.size)
        assertEquals("active-ws", active[0].name)

        assertNull(workspaceRepository.findByNameAndDeletedFalse("deleted-ws"))
    }

    @Test
    fun `soft delete excludes channel from queries`() {
        val workspace =
            workspaceRepository.save(
                SlackWorkspace(
                    name = "jvm-news-del",
                    botToken = SecretRef.literal("xoxb-test"),
                    appToken = SecretRef.literal("xapp-test"),
                )
            )
        channelRepository.save(SlackChannel(workspace = workspace, name = "#active-ch"))
        channelRepository.save(
            SlackChannel(workspace = workspace, name = "#deleted-ch", deleted = true)
        )

        val channels = channelRepository.findByWorkspaceAndDeletedFalse(workspace)
        assertEquals(1, channels.size)
        assertEquals("#active-ch", channels[0].name)

        assertNull(
            channelRepository.findByWorkspaceAndNameAndDeletedFalse(workspace, "#deleted-ch")
        )
    }

    @Test
    fun `workspace toSummary produces readable output`() {
        val workspace =
            SlackWorkspace(
                name = "jvm-news",
                botToken = SecretRef.literal("xoxb-test"),
                appToken = SecretRef.literal("xapp-test"),
                autoconnect = true,
            )
        assertEquals("jvm-news (autoconnect)", workspace.toSummary())
    }

    @Test
    fun `workspace toSummary manual mode`() {
        val workspace =
            SlackWorkspace(
                name = "jvm-news",
                botToken = SecretRef.literal("xoxb-test"),
                appToken = SecretRef.literal("xapp-test"),
                autoconnect = false,
            )
        assertEquals("jvm-news (manual)", workspace.toSummary())
    }

    @Test
    fun `channel provenanceUri encodes correctly`() {
        val workspace =
            workspaceRepository.save(
                SlackWorkspace(
                    name = "jvm-news-uri",
                    botToken = SecretRef.literal("xoxb-test"),
                    appToken = SecretRef.literal("xapp-test"),
                )
            )
        val channel = channelRepository.save(SlackChannel(workspace = workspace, name = "#java"))
        val uri = channel.provenanceUri()
        assertEquals("slack://jvm-news-uri/%23java", uri)
    }
}
