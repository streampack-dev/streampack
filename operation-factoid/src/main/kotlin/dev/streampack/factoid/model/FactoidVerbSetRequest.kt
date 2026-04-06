/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/** Typed request for the "set" verb command: set selector.attribute value */
data class FactoidVerbSetRequest(
    val selector: String,
    val attribute: FactoidAttributeType,
    val value: String,
)
