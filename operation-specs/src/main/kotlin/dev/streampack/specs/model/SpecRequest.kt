/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.model

data class SpecRequest(val type: SpecType, val identifier: Int) {

    /** The factoid-style selector, e.g. "jsr 52" or "rfc 8712" */
    val selector: String
        get() = "${type.name.lowercase()} $identifier"

    /** The URL for this spec */
    val url: String
        get() = type.urlTemplate.format(identifier)
}
