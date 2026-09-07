/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mattermost.model

/** Lightweight channel descriptor resolved from the Mattermost REST API. */
data class MattermostChannelRef(
    val id: String,
    val name: String,
    val displayName: String? = null,
    val teamId: String? = null,
    val teamName: String? = null,
    val type: String? = null,
) {
    fun summary(): String {
        val label = displayName?.takeIf { it.isNotBlank() } ?: name
        val teamSuffix = teamName?.let { " on $it" } ?: ""
        return "$label [$id]$teamSuffix"
    }
}
