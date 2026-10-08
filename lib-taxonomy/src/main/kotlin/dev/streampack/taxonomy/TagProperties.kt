/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Settings for the tag vocabulary (#140).
 *
 * [aiNearMiss]: when AI is enabled, each new tag is sent, after the write that created it, to the
 * moderation model with the existing vocabulary, and its answer ranks and explains the tag's review
 * entry. On by default; nothing happens without AI.
 *
 * [aiAutoApplyThreshold]: when set (0 to 1), a near-miss answered at or above it is applied at
 * once, the new tag aliased to the candidate, recorded as done by `ai:<model>`. Off (null) by
 * default: a wrong merge (`java`/`javascript`) is quiet damage.
 */
@ConfigurationProperties(prefix = "streampack.tags")
data class TagProperties(
    val aiNearMiss: Boolean = true,
    val aiAutoApplyThreshold: Double? = null,
)
