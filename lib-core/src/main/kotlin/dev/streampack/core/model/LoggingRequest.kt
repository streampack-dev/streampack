/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * Payload type for log-only messages dispatched through ingress by protocol adapters.
 *
 * This is intended for events that should appear in the message log but have no meaningful
 * operation response -- joins, parts, quits, nick changes and topic changes. The ingress wire tap
 * captures the message unconditionally, recording its [kind] (#174); the operation chain
 * short-circuits to NotHandled without ever consulting any operation.
 *
 * No operation should ever handle this type. Enforcement is in OperationService.processChain().
 */
data class LoggingRequest(val content: String, val kind: MessageKind = MessageKind.MESSAGE) {
    override fun toString(): String = content
}
