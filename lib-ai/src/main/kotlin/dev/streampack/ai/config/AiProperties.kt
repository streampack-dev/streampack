/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ai.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Configuration for the AI bridge layer */
@ConfigurationProperties(prefix = "streampack.ai")
data class AiProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = "claude-sonnet-4-5-20250929",
    val maxTokens: Int = 1024,
)
