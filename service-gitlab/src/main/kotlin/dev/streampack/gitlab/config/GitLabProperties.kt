/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** Configuration properties for GitLab project watching (`streampack.gitlab.*`) */
@ConfigurationProperties(prefix = "streampack.gitlab")
data class GitLabProperties(
    /** The module registers no beans unless this is true */
    val enabled: Boolean = false,
    val pollInterval: Duration = Duration.ofMinutes(60),
    val schedulerInterval: Duration = Duration.ofSeconds(90),
    val batchSize: Int = 5,
    val maxBackoff: Duration = Duration.ofDays(1),
    val connectTimeoutSeconds: Int = 5,
    val readTimeoutSeconds: Int = 10,
    val webhookSecretKey: String = "",
    val webhookBaseUrl: String? = null,
    val deliveryDedupeTtl: Duration = Duration.ofHours(6),
)
