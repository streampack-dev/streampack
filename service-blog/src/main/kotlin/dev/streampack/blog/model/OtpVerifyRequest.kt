/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.model

import dev.streampack.core.model.CodeChannel
import dev.streampack.core.model.CodeIdentity

/** Verification of a one-time code for the same identity shape as [OtpRequest] */
data class OtpVerifyRequest(
    val email: String? = null,
    val code: String = "",
    val channel: CodeChannel = CodeChannel.EMAIL,
    val address: String? = null,
    val server: String? = null,
) {
    fun identity(): CodeIdentity? {
        val target = (address ?: email)?.trim()?.ifBlank { null } ?: return null
        return CodeIdentity(channel, target, server?.trim()?.ifBlank { null })
    }
}
