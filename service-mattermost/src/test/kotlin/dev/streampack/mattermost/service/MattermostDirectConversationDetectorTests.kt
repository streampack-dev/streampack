/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.mattermost.entity.MattermostChannel
import dev.streampack.mattermost.entity.MattermostServer
import dev.streampack.mattermost.repository.MattermostChannelRepository
import dev.streampack.mattermost.repository.MattermostServerRepository
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class MattermostDirectConversationDetectorTests {
    private val servers = Mockito.mock(MattermostServerRepository::class.java)
    private val channels = Mockito.mock(MattermostChannelRepository::class.java)
    private val detector = MattermostDirectConversationDetector(servers, channels)
    private val work = MattermostServer(name = "work", baseUrl = "https://mm.example.com")

    private fun registered(channelId: String, type: String?) {
        Mockito.`when`(channels.findByServerAndChannelIdAndDeletedFalse(work, channelId))
            .thenReturn(
                MattermostChannel(
                    server = work,
                    name = channelId,
                    channelId = channelId,
                    channelType = type,
                )
            )
    }

    private fun reply(channelId: String, metadata: Map<String, Any> = emptyMap()) =
        Provenance(
            protocol = Protocol.MATTERMOST,
            serviceId = "work",
            replyTo = channelId,
            metadata = metadata,
        )

    @Test
    fun `a reply to a registered direct or group channel is direct`() {
        Mockito.`when`(servers.findByNameAndDeletedFalse("work")).thenReturn(work)
        registered("dmchannel", "D")
        registered("groupchannel", "G")
        registered("townsquare", "O")

        assertTrue(detector.isDirect(reply("dmchannel")))
        assertTrue(detector.isDirect(reply("groupchannel")))
        assertFalse(detector.isDirect(reply("townsquare")))
        assertFalse(detector.isDirect(reply("unregistered")))
    }

    @Test
    fun `a post's own channel type is left to the core rule`() {
        Mockito.`when`(servers.findByNameAndDeletedFalse("work")).thenReturn(work)
        registered("dmchannel", "D")

        assertFalse(detector.isDirect(reply("dmchannel", mapOf("channelType" to "O"))))
        Mockito.verifyNoInteractions(channels)
    }
}
