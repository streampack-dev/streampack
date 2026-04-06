/* Joseph B. Ottinger (C)2026 */
package dev.streampack.discord.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Controls whether the Discord connection infrastructure is activated */
@ConfigurationProperties(prefix = "streampack.discord")
data class DiscordProperties(
    val enabled: Boolean = false,
    val token: String = "",
    val applicationId: String = "",
    val publicKey: String = "",
    val permissions: Int = 3072,
    val signalCharacter: String = "!",
)
