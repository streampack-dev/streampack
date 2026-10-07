/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.OtpVerifyRequest
import dev.streampack.blog.service.UserConvergenceService
import dev.streampack.core.model.CodeIdentity
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.ThrottlePolicy
import dev.streampack.core.service.CodeDelivery
import dev.streampack.core.service.OneTimeCodeService
import dev.streampack.core.service.ThrottleService
import dev.streampack.core.service.TypedOperation
import java.time.Duration
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Validates a one-time passcode for an identity on any channel and authenticates the user, creating
 * an account if needed. The identity is resolved through the channel's delivery again, so a code
 * can only be redeemed by the recipient it was issued to.
 *
 * Attempts are throttled per identity as given, before it is resolved, so an identity that doesn't
 * exist is throttled exactly like one that does. Wrong guesses are also counted by
 * [OneTimeCodeService], which expires the recipient's codes after too many; that answers with the
 * same [INVALID] as a wrong or expired code.
 */
@Component
class OtpVerifyOperation(
    private val oneTimeCodeService: OneTimeCodeService,
    private val userConvergenceService: UserConvergenceService,
    private val throttleService: ThrottleService,
    deliveries: List<CodeDelivery>,
) : TypedOperation<OtpVerifyRequest>(OtpVerifyRequest::class) {
    private val deliveries = deliveries.associateBy { it.channel }

    override fun handle(payload: OtpVerifyRequest, message: Message<*>): OperationOutcome {
        val identity = payload.identity() ?: return OperationResult.Error(INVALID)
        if (!throttleService.tryAcquire(throttleKey(identity), PER_IDENTITY)) {
            return OperationResult.Error(TOO_MANY_ATTEMPTS)
        }
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

    /** Case is folded so that varying it doesn't buy a fresh bucket. */
    private fun throttleKey(identity: CodeIdentity): String =
        "otp-verify:${identity.channel}:${identity.server.orEmpty().lowercase()}:" +
            identity.address.lowercase()

    companion object {
        const val INVALID = "Invalid or expired code"
        const val TOO_MANY_ATTEMPTS = "Too many attempts; try again shortly."

        /**
         * Ten tries at once, then one a minute: room for a few typos and a second code, while a
         * guesser gets about 1,440 tries a day per identity on top of the wrong-guess limit.
         */
        val PER_IDENTITY = ThrottlePolicy(10, Duration.ofMinutes(10))
    }
}
