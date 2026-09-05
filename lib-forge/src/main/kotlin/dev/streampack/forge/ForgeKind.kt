/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge

/** A code-hosting forge. [changeRequestNoun] is how that forge names a pull/merge request. */
enum class ForgeKind(val displayName: String, val changeRequestNoun: String) {
    GITHUB("GitHub", "PR"),
    GITLAB("GitLab", "MR"),
}
