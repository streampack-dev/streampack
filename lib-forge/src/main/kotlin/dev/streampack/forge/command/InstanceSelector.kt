/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.command

/**
 * The `on <host>` suffix shared by every forge command that names a project: `owner/repo on
 * ghe.example.com`. A null [host] means the forge's hosted default.
 *
 * Commands that go through the management base class receive the identifier with any `to <uri>`
 * already stripped, so only the trailing `on <host>` remains to be split here.
 */
data class InstanceSelector(val identifier: String, val host: String?) {
    companion object {
        private const val KEYWORD = "on"

        fun parse(text: String): InstanceSelector {
            val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.size >= 3 && tokens[tokens.size - 2].equals(KEYWORD, ignoreCase = true)) {
                val identifier = tokens.dropLast(2).joinToString(" ")
                return InstanceSelector(identifier, normalizeHost(tokens.last()))
            }
            return InstanceSelector(tokens.joinToString(" "), null)
        }

        /** Hosts compare case-insensitively; store and match them in lowercase. */
        fun normalizeHost(host: String): String = host.trim().lowercase()
    }
}
