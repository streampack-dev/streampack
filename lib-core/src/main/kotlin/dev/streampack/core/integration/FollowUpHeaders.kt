/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.integration

import org.springframework.messaging.MessageHeaders

/**
 * The headers a follow-up message (one an operation sends because of the message it's handling)
 * should carry from that message: its provenance, correlation and the like, but not the transport's
 * bookkeeping. A follow-up that kept the parent's reply and error channels could answer the
 * parent's caller in its place: a request-and-reply caller (an HTTP endpoint using
 * [EventGateway.process]) would then get the follow-up's result instead of its own.
 */
fun MessageHeaders.forFollowUp(): Map<String, Any?> = filterKeys {
    it != MessageHeaders.REPLY_CHANNEL && it != MessageHeaders.ERROR_CHANNEL
}
