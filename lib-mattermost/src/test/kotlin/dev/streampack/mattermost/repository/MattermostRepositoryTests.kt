/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.repository

import dev.streampack.core.model.SecretRef
import dev.streampack.mattermost.entity.MattermostChannel
import dev.streampack.mattermost.entity.MattermostServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class MattermostRepositoryTests {

    @Autowired lateinit var serverRepository: MattermostServerRepository
    @Autowired lateinit var channelRepository: MattermostChannelRepository

    @Test
    fun `can persist server and channel`() {
        val server =
            serverRepository.save(
                MattermostServer(
                    name = "work",
                    baseUrl = "https://mattermost.example.com",
                    token = SecretRef.literal("token-123"),
                )
            )

        val channel =
            channelRepository.save(
                MattermostChannel(
                    server = server,
                    name = "town-square",
                    channelId = "abc123channelid00000000000",
                    teamId = "teamid00000000000000000000",
                    channelType = "O",
                )
            )

        val foundServer = serverRepository.findByNameAndDeletedFalse("work")
        val foundChannel =
            channelRepository.findByServerAndChannelIdAndDeletedFalse(server, channel.channelId)

        assertNotNull(foundServer)
        assertNotNull(foundChannel)
        assertEquals("town-square", foundChannel!!.name)
        assertEquals("mattermost://work/${channel.channelId}", foundChannel.provenanceUri())
    }
}
