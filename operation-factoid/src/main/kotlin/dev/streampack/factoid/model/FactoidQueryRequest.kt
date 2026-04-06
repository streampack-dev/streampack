/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/** Typed request to look up a factoid by selector and attribute */
data class FactoidQueryRequest(val selector: String, val attribute: FactoidAttributeType)
