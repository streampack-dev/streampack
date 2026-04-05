/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * An operation recognized the message but chose not to handle it.
 *
 * This is a non-terminal signal: the operation chain continues to the next candidate. The
 * [OperationService][com.enigmastation.streampack.core.service.OperationService] logs the decline
 * with operation context, so the operation itself only needs to provide the reason.
 */
data class Declined(val reason: String) : OperationOutcome
