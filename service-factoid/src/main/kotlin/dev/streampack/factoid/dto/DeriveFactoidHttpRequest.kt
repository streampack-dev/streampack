/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.dto

/** HTTP body for drafting a factoid: the name, and the writing it came from. */
data class DeriveFactoidHttpRequest(val selector: String? = "", val context: String? = "")
