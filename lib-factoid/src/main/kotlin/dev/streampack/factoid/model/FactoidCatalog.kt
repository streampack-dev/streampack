/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.model

/** Asks for every factoid's selector and definition text, for matching against writing (#130). */
data object FindFactoidCatalogRequest

/** Every factoid: its selector, and its definition text when it has one. */
data class FactoidCatalog(val entries: List<FactoidCatalogEntry>) {
    private val bySelector = entries.associateBy { it.selector.lowercase() }

    /** The factoid named [selector], ignoring case. */
    fun find(selector: String): FactoidCatalogEntry? = bySelector[selector.trim().lowercase()]
}

data class FactoidCatalogEntry(val selector: String, val text: String? = null)
