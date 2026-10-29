/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/**
 * Data the server keeps for an extension (#294), so an extension never ships SQL or touches the
 * schema. Each document is a JSON object, found by the extension's name, a collection the extension
 * names, and an id within the collection.
 */
interface ExtensionDataStore {
    /** Returns the document, or null when there is none. */
    fun get(extension: String, collection: String, id: String): Map<String, Any>?

    /** Creates or replaces the document. */
    fun put(extension: String, collection: String, id: String, data: Map<String, Any>)

    /** Removes the document. Returns false when there was none. */
    fun delete(extension: String, collection: String, id: String): Boolean

    /** Every document in the collection, by id. */
    fun list(extension: String, collection: String): Map<String, Map<String, Any>>
}
