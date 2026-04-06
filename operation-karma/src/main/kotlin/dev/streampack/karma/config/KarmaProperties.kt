/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "streampack.karma")
data class KarmaProperties(
    val immuneSubjects: List<String> = emptyList(),
    val maxSubjectLength: Int = 45,
)
