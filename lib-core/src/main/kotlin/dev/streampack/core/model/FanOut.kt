/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

import org.springframework.messaging.Message

/**
 * An operation produced multiple independently-addressed messages that each need processing.
 *
 * This is a non-terminal signal consumed by the OperationService: it dispatches each child message
 * through the operation chain and returns a Success to the original caller with a dispatch count.
 * Adapters and gateway callers never see FanOut -- it is internal to the chain, like [Declined].
 */
data class FanOut(val messages: List<Message<*>>) : OperationOutcome {
    companion object {
        /** Message header tracking re-entry depth during fan-out dispatch */
        const val HOP_COUNT_HEADER = "streampack_hopCount"
    }
}
