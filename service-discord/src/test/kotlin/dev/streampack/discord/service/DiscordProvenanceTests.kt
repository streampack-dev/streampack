/* Joseph B. Ottinger (C)2026 */
package dev.streampack.discord.service

import dev.streampack.core.model.Protocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiscordProvenanceTests {

    @Test
    fun `guild channel provenance uses channel id as routing key`() {
        val provenance =
            DiscordProvenance.guildChannel(
                guildId = "1256590312177012806",
                guildName = "Primate Server",
                channelId = "222222222222222222",
                channelName = "general",
                categoryId = "111111111111111111",
                categoryName = "rcompat",
            )

        assertEquals(Protocol.DISCORD, provenance.protocol)
        assertEquals("1256590312177012806", provenance.serviceId)
        assertEquals("222222222222222222/Primate Server/rcompat/#general", provenance.replyTo)
        assertEquals(
            "discord://1256590312177012806/222222222222222222/Primate%20Server/rcompat/%23general",
            provenance.encode(),
        )
        assertEquals("222222222222222222", DiscordProvenance.channelIdOrNull(provenance.replyTo))
        assertEquals("Primate Server", provenance.metadata["guildName"])
        assertEquals("general", provenance.metadata["channelName"])
        assertEquals("111111111111111111", provenance.metadata["categoryId"])
        assertEquals("rcompat", provenance.metadata["categoryName"])
    }

    @Test
    fun `canonical routing ignores display labels after channel id`() {
        assertEquals(
            "222222222222222222",
            DiscordProvenance.channelIdOrNull(
                "222222222222222222/Old Server/old-category/#old-name"
            ),
        )
    }

    @Test
    fun `legacy channel name provenance is detected and has no stable channel id`() {
        assertTrue(DiscordProvenance.isLegacyGuildChannelReplyTo("#general"))
        assertNull(DiscordProvenance.channelIdOrNull("#general"))
    }

    @Test
    fun `direct message reply targets are not legacy guild channels`() {
        assertFalse(DiscordProvenance.isLegacyGuildChannelReplyTo("987654321098765432"))
        assertEquals("987654321098765432", DiscordProvenance.channelIdOrNull("987654321098765432"))
    }
}
