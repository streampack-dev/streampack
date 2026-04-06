/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "streampack.rss")
data class RssProperties(
    val connectTimeoutSeconds: Int = 5,
    val readTimeoutSeconds: Int = 10,
    val pollInterval: Duration = Duration.ofHours(1),
)
