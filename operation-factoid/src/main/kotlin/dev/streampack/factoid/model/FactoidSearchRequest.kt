/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/** Typed request to search for factoids matching a term */
data class FactoidSearchRequest(val term: String)
