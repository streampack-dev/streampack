/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * An operation handled a message internally and intentionally produced no caller-visible result.
 *
 * This is a non-terminal internal signal consumed by the OperationService. It stops the operation
 * chain like a terminal result would, but nothing is published to egress.
 */
data class Consumed(val reason: String? = null) : OperationOutcome
