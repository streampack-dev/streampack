/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/** Typed request to create or update a factoid attribute */
data class FactoidSetRequest(
    val selector: String,
    val attribute: FactoidAttributeType,
    val value: String,
)
