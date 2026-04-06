/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Controls whether the Slack connection infrastructure is activated */
@ConfigurationProperties(prefix = "streampack.slack")
data class SlackProperties(val enabled: Boolean = false, val signalCharacter: String = "!")
