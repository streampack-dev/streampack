/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectConversationsTests {
    private val conversations = DirectConversations(emptyList())

    private fun provenance(
        protocol: Protocol,
        replyTo: String,
        serviceId: String? = "net",
        metadata: Map<String, Any> = emptyMap(),
    ) =
        Provenance(
            protocol = protocol,
            serviceId = serviceId,
            replyTo = replyTo,
            metadata = metadata,
        )

    @Test
    fun `an IRC nick is direct, a channel by any prefix is not`() {
        assertTrue(conversations.isDirect(provenance(Protocol.IRC, "alice")))
        assertTrue(conversations.isDirect(provenance(Protocol.IRC, "[alice]")))
        for (channel in listOf("#java", "&local", "+modeless", "!safe")) {
            assertFalse(conversations.isDirect(provenance(Protocol.IRC, channel)), channel)
        }
    }

    @Test
    fun `a Discord DM has no guild`() {
        assertTrue(conversations.isDirect(provenance(Protocol.DISCORD, "123456789012345678", null)))
        assertFalse(
            conversations.isDirect(
                provenance(Protocol.DISCORD, "123456789012345678/Guild/#general")
            )
        )
    }

    @Test
    fun `a Slack DM is a user id, a group DM is known by its type`() {
        assertTrue(conversations.isDirect(provenance(Protocol.SLACK, "U0123ABCD")))
        assertTrue(conversations.isDirect(provenance(Protocol.SLACK, "W0123ABCD")))
        assertFalse(conversations.isDirect(provenance(Protocol.SLACK, "C0123ABCD")))
        assertTrue(
            conversations.isDirect(
                provenance(Protocol.SLACK, "C0123ABCD", metadata = mapOf("channelType" to "mpim"))
            )
        )
    }

    @Test
    fun `a Mattermost direct or group channel is known by its type`() {
        val id = "abcdefghijklmnopqrstuvwxyz"
        for (type in listOf("D", "G")) {
            assertTrue(
                conversations.isDirect(
                    provenance(Protocol.MATTERMOST, id, metadata = mapOf("channelType" to type))
                )
            )
        }
        for (type in listOf("O", "P")) {
            assertFalse(
                conversations.isDirect(
                    provenance(Protocol.MATTERMOST, id, metadata = mapOf("channelType" to type))
                )
            )
        }
    }

    @Test
    fun `a detector can say a conversation is direct`() {
        val withDetector =
            DirectConversations(
                listOf(
                    object : DirectConversationDetector {
                        override fun isDirect(provenance: Provenance) =
                            provenance.replyTo == "secret-room"
                    }
                )
            )
        assertTrue(withDetector.isDirect(provenance(Protocol.MATTERMOST, "secret-room")))
        assertFalse(withDetector.isDirect(provenance(Protocol.MATTERMOST, "town-square")))
        assertFalse(conversations.isDirect(provenance(Protocol.CONSOLE, "local")))
    }
}
