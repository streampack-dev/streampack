/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

/** Optional resolver for wiki links that target factoids. */
interface FactoidWikiLinkResolver {
    /**
     * Resolve link metadata for a factoid selector.
     *
     * Return null if the selector is unknown or no metadata override should be applied.
     */
    fun resolve(selector: String): FactoidWikiLinkMetadata?
}

data class FactoidWikiLinkMetadata(val href: String? = null, val title: String? = null)
