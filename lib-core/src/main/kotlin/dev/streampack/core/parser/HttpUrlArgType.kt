/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.parser

import java.net.URI

/** URL validator that accepts only absolute http/https URLs with a host. */
data object HttpUrlArgType : CommandArgType<String> {
    override fun parse(token: String): String? {
        val uri =
            try {
                URI(token.trim())
            } catch (_: Exception) {
                return null
            }
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toString()
    }
}
