/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.OtpVerifyRequest
import dev.streampack.blog.service.UserConvergenceService
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.CodeDelivery
import dev.streampack.core.service.OneTimeCodeService
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Validates a one-time passcode for an identity on any channel and authenticates the user, creating
 * an account if needed. The identity is resolved through the channel's delivery again, so a code
 * can only be redeemed by the recipient it was issued to.
 */
@Component
class OtpVerifyOperation(
    private val oneTimeCodeService: OneTimeCodeService,
    private val userConvergenceService: UserConvergenceService,
    deliveries: List<CodeDelivery>,
) : TypedOperation<OtpVerifyRequest>(OtpVerifyRequest::class) {
    private val deliveries = deliveries.associateBy { it.channel }

    override fun handle(payload: OtpVerifyRequest, message: Message<*>): OperationOutcome {
        val identity = payload.identity() ?: return OperationResult.Error(INVALID)
        val delivery = deliveries[identity.channel] ?: return OperationResult.Error(INVALID)
        val recipient = delivery.resolve(identity) ?: return OperationResult.Error(INVALID)
        if (!oneTimeCodeService.consumeCode(identity.channel, recipient.key, payload.code)) {
            return OperationResult.Error(INVALID)
        }

        return try {
            val email = recipient.email
            val loginResponse =
                if (email != null) {
                    userConvergenceService.converge(email)
                } else {
                    userConvergenceService.convergeIdentity(recipient)
                }
            OperationResult.Success(loginResponse)
        } catch (e: IllegalStateException) {
            OperationResult.Error(e.message ?: "Authentication failed")
        }
    }

    companion object {
        const val INVALID = "Invalid or expired code"
    }
}
