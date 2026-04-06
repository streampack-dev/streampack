/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/** Request to look up text/url metadata for a factoid selector. */
data class FindFactoidLinkMetadataRequest(val selector: String)

/** Shared read-model payload for factoid text/url link metadata. */
data class FactoidLinkMetadataResponse(
    val selector: String,
    val text: String? = null,
    val urls: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val seeAlso: List<String> = emptyList(),
)
