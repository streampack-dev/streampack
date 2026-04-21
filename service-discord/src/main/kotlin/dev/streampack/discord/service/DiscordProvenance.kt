/* Joseph B. Ottinger (C)2026 */
package dev.streampack.discord.service

import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance

/** Builds and parses Discord provenance without depending on JDA runtime types. */
object DiscordProvenance {
    private val snowflakePattern = Regex("\\d{15,25}")

    fun guildChannel(
        guildId: String,
        guildName: String,
        channelId: String,
        channelName: String,
        categoryId: String? = null,
        categoryName: String? = null,
        botNick: String? = null,
        user: dev.streampack.core.model.UserPrincipal? = null,
    ): Provenance {
        val labels =
            listOfNotNull(
                guildName.toDisplaySegment(),
                categoryName?.toDisplaySegment(),
                "#${channelName.removePrefix("#")}".toDisplaySegment(),
            )
        val metadata =
            buildMap<String, Any> {
                put("guildName", guildName)
                put("channelId", channelId)
                put("channelName", channelName.removePrefix("#"))
                categoryId?.let { put("categoryId", it) }
                categoryName?.let { put("categoryName", it) }
                botNick?.let { put(Provenance.BOT_NICK, it) }
            }

        return Provenance(
            protocol = Protocol.DISCORD,
            serviceId = guildId,
            replyTo = listOf(channelId).plus(labels).joinToString("/"),
            user = user,
            metadata = metadata,
        )
    }

    fun channelIdOrNull(replyTo: String): String? {
        val firstSegment = replyTo.substringBefore("/")
        return firstSegment.takeIf { snowflakePattern.matches(it) }
    }

    fun isLegacyGuildChannelReplyTo(replyTo: String): Boolean = replyTo.startsWith("#")

    private fun String.toDisplaySegment(): String = trim().replace("/", "-")
}
