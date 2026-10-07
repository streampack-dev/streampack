/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ai.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration for the AI bridge layer.
 *
 * [timeout] bounds each call to the model, and [maxRetries] how often a failed one is tried again,
 * so a call that stalls ends with a logged failure rather than holding its thread (the Anthropic
 * client's own default is ten minutes a try, and a blocked call doesn't answer an interrupt).
 */
@ConfigurationProperties(prefix = "streampack.ai")
data class AiProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = "claude-sonnet-4-5-20250929",
    val maxTokens: Int = 1024,
    val timeout: Duration = Duration.ofSeconds(60),
    val maxRetries: Int = 1,
)
