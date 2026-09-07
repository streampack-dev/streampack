/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.OtpRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.CodeDelivery
import dev.streampack.core.service.OneTimeCodeService
import dev.streampack.core.service.TypedOperation
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Generates a one-time passcode and hands it to the delivery for the requested channel. The
 * response is the same whether the identity was known, unknown, throttled, or on a channel this
 * deployment does not offer: nothing here may reveal who exists.
 */
@Component
class OtpRequestOperation(
    private val oneTimeCodeService: OneTimeCodeService,
    deliveries: List<CodeDelivery>,
) : TypedOperation<OtpRequest>(OtpRequest::class) {
    private val deliveries = deliveries.associateBy { it.channel }

    override fun handle(payload: OtpRequest, message: Message<*>): OperationOutcome {
        val identity = payload.identity()
        val delivery = identity?.let { deliveries[it.channel] }
        if (identity == null || delivery == null) {
            logger.debug("OTP request on unavailable channel {} ignored", payload.channel)
            return OperationResult.Success(SENT_MESSAGE)
        }
        try {
            val recipient = delivery.resolve(identity)
            if (recipient == null) {
                logger.debug("OTP request for unknown {} identity ignored", identity.channel)
                return OperationResult.Success(SENT_MESSAGE)
            }
            val otc = oneTimeCodeService.generateCode(identity.channel, recipient.key)
            delivery.deliver(recipient, otc.code)
        } catch (e: IllegalStateException) {
            logger.warn("OTP rate limit hit on {} for {}", identity.channel, identity.address)
        } catch (e: Exception) {
            logger.error("Failed to send OTP code on {}: {}", identity.channel, e.message)
        }
        return OperationResult.Success(SENT_MESSAGE)
    }

    companion object {
        /** Deliberately channel-neutral and identical for every outcome */
        const val SENT_MESSAGE = "If that identity is registered or valid, a code has been sent"
    }
}
