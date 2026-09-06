/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Feed polling runs in bounded batches (issue #40): every [schedulerInterval] the poller takes the
 * [batchSize] oldest-due feeds; a polled feed is next due [pollInterval] later, or later still
 * after failures, up to [maxBackoff].
 */
@ConfigurationProperties(prefix = "streampack.rss")
data class RssProperties(
    val connectTimeoutSeconds: Int = 5,
    val readTimeoutSeconds: Int = 10,
    val pollInterval: Duration = Duration.ofHours(1),
    val schedulerInterval: Duration = Duration.ofSeconds(90),
    val batchSize: Int = 5,
    val maxBackoff: Duration = Duration.ofDays(1),
)
