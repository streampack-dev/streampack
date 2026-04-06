/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.OtpRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.EmailService
import dev.streampack.core.service.OneTimeCodeService
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Generates a one-time passcode and sends it to the requested email address */
@Component
class OtpRequestOperation(
    private val oneTimeCodeService: OneTimeCodeService,
    private val emailService: EmailService,
) : TypedOperation<OtpRequest>(OtpRequest::class) {

    override fun handle(payload: OtpRequest, message: Message<*>): OperationOutcome {
        try {
            val otc = oneTimeCodeService.generateCode(payload.email)
            emailService.sendOneTimeCode(payload.email, otc.code)
        } catch (e: IllegalStateException) {
            logger.warn("OTP rate limit hit for {}", payload.email)
        } catch (e: Exception) {
            logger.error("Failed to send OTP code to {}: {}", payload.email, e.message)
        }
        /* Never reveal whether the email exists or whether the code was actually sent */
        return OperationResult.Success("If that email is registered or valid, a code has been sent")
    }
}
