/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * Terminal outcomes that leave the operation chain and reach the caller via the EventGateway.
 * - [Success] when an operation produced a meaningful response
 * - [Error] when an operation encountered a failure (this is still a definitive answer)
 * - [NotHandled] when no operation in the chain could handle the message
 *
 * Both Success and Error short-circuit the chain -- they are definitive answers. NotHandled is
 * returned only when the entire chain has been exhausted without a result.
 *
 * [FanOut] is intentionally an [OperationOutcome], not an OperationResult. It is consumed
 * internally by the OperationService and never escapes to callers, so exhaustive `when` blocks on
 * OperationResult remain unchanged.
 *
 * See [OperationOutcome] for the full family including non-terminal signals like [Declined] and
 * [FanOut].
 */
sealed class OperationResult : OperationOutcome {
    data class Success(
        val payload: Any,
        val loopback: Boolean = false,
        val provenance: Provenance? = null,
    ) : OperationResult()

    data class Error(val message: String) : OperationResult()

    data object NotHandled : OperationResult()
}
