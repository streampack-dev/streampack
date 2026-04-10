/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * What an operation can produce when it processes a message.
 *
 * This is the return type of [Operation.execute()][dev.streampack.core.service.Operation.execute].
 * It separates into two families:
 * - [OperationResult] (Success, Error, NotHandled) -- terminal outcomes that leave the operation
 *   chain and reach the caller via the EventGateway.
 * - [Declined] -- a non-terminal signal consumed by the OperationService. It continues the chain
 *   but carries diagnostic information for logging.
 * - [Consumed] -- a non-terminal internal signal consumed by the OperationService. It stops the
 *   chain without publishing anything to egress.
 * - [FanOut] -- a non-terminal signal consumed by the OperationService. It dispatches each child
 *   message through the operation chain and returns Success to the original caller.
 *
 * Adapters and gateway callers only see [OperationResult]; [Declined], [Consumed], and [FanOut] are
 * internal to the chain.
 */
sealed interface OperationOutcome
