/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** Controls whether the Mattermost connection infrastructure is activated. */
@ConfigurationProperties(prefix = "streampack.mattermost")
data class MattermostProperties(
    val enabled: Boolean = false,
    val signalCharacter: String = "!",
    val reconnectDelay: Duration = Duration.ofSeconds(15),
)
