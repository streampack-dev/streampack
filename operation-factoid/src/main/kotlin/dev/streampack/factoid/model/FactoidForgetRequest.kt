/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/** Typed request to forget a factoid or a specific attribute */
data class FactoidForgetRequest(val selector: String, val attribute: FactoidAttributeType?)
