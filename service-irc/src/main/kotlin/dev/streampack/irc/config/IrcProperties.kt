/* Joseph B. Ottinger (C)2026 */
package dev.streampack.irc.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Controls whether the IRC connection infrastructure is activated */
@ConfigurationProperties(prefix = "streampack.irc")
data class IrcProperties(
    val enabled: Boolean = false,
    val signalCharacter: String = "!",
    val identity: String = "Nevet IRC Bridge",
    val adaptiveSendDelayEnabled: Boolean = true,
    val minSendDelayMs: Int = 120,
    val maxSendDelayMs: Int = 1000,
    val sendDelayRampUpFactor: Double = 1.1,
    val sendDelayRampDownFactor: Double = 0.9,
    val sendDelayMs: Int = 1200,
)
