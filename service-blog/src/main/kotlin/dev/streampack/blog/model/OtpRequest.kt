/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import dev.streampack.core.model.CodeChannel
import dev.streampack.core.model.CodeIdentity

/**
 * Request for a one-time sign-in code. The original `{email}` body is the email channel; the
 * general form is `{channel, address, server?}`, where [server] names the registered chat server
 * for chat channels.
 */
data class OtpRequest(
    val email: String? = null,
    val channel: CodeChannel = CodeChannel.EMAIL,
    val address: String? = null,
    val server: String? = null,
) {
    /** The identity this request names, or null when neither an address nor an email was given */
    fun identity(): CodeIdentity? {
        val target = (address ?: email)?.trim()?.ifBlank { null } ?: return null
        return CodeIdentity(channel, target, server?.trim()?.ifBlank { null })
    }
}
