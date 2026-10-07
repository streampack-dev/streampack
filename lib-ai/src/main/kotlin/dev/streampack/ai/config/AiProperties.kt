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
 *
 * [thinking] asks the model to reason before it answers (adaptive thinking, its text omitted). Off
 * by default, which sends nothing about thinking at all: not every model accepts having it turned
 * off (Opus 5.5 refuses `thinking.type.disabled`), and some think on their own.
 *
 * [effort] (low, medium, high, xhigh, max) sets how much effort a model that supports it spends,
 * thinking included: `low` is the least thinking such a model allows. Sent only when set, as not
 * every model takes it.
 *
 * [moderationModel] is a cheaper model for high-volume or background work, such as abuse detection,
 * reached through [dev.streampack.ai.service.AiService.moderation]. It shares the timeout, retries
 * and token limit; [thinking] and [effort] belong to [model] alone, since the cheaper model may not
 * take them (Haiku 4.5 refuses an effort).
 */
@ConfigurationProperties(prefix = "streampack.ai")
data class AiProperties(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val model: String = "claude-opus-5-5",
    val maxTokens: Int = 1024,
    val timeout: Duration = Duration.ofSeconds(60),
    val maxRetries: Int = 1,
    val thinking: Boolean = false,
    val effort: String? = null,
    val moderationModel: String = "claude-haiku-4-5-20251001",
)
