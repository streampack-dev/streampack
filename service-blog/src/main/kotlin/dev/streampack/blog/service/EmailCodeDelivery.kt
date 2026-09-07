/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.core.model.CodeChannel
import dev.streampack.core.model.CodeIdentity
import dev.streampack.core.model.ResolvedRecipient
import dev.streampack.core.service.CodeDelivery
import dev.streampack.core.service.EmailService
import org.springframework.stereotype.Component

/**
 * Codes by email. Every syntactically plausible address resolves: sign-up by email is open, and an
 * account is created on first verify, as it always has been.
 */
@Component
class EmailCodeDelivery(private val emailService: EmailService) : CodeDelivery {
    override val channel = CodeChannel.EMAIL

    override fun resolve(identity: CodeIdentity): ResolvedRecipient? {
        val email = identity.address.trim().lowercase()
        if (!email.contains('@') || email.startsWith("@") || email.endsWith("@")) return null
        return ResolvedRecipient(key = email, email = email)
    }

    override fun deliver(recipient: ResolvedRecipient, code: String) {
        emailService.sendOneTimeCode(recipient.email ?: recipient.key, code)
    }
}
