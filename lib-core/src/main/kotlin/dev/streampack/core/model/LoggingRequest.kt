/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * Payload type for log-only messages dispatched through ingress by protocol adapters.
 *
 * This is intended for events that should appear in the message log but have no meaningful
 * operation response -- joins, parts, topic changes, CTCP actions, and similar metadata events. The
 * ingress wire tap captures the message unconditionally; the operation chain short-circuits to
 * NotHandled without ever consulting any operation.
 *
 * No operation should ever handle this type. Enforcement is in OperationService.processChain().
 */
data class LoggingRequest(val content: String) {
    override fun toString(): String = content
}
