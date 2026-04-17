/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

/** Summary of a feed import run. */
data class RssImportSummary(
    val added: Int = 0,
    val alreadyExisted: Int = 0,
    val discoveryFailed: Int = 0,
    val ignored: Int = 0,
)
