/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge

/**
 * A code-hosting forge. [changeRequestNoun] is how that forge names a pull/merge request and
 * [changeRequestPrefix] how it writes the number (`#12`, `!12`); [defaultHost] is the hosted
 * instance used when a command omits `on <host>`.
 */
enum class ForgeKind(
    val displayName: String,
    val changeRequestNoun: String,
    val changeRequestPrefix: String,
    val defaultHost: String,
) {
    GITHUB("GitHub", "PR", "#", "github.com"),
    GITLAB("GitLab", "MR", "!", "gitlab.com"),
}
