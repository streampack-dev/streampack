/* Joseph B. Ottinger (C)2026 */
package dev.streampack.specs.service

import dev.streampack.core.service.PageFetcher
import dev.streampack.specs.model.SpecRequest
import dev.streampack.specs.model.SpecType
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Fetches spec titles from RFC Editor, OpenJDK, and JCP websites */
@Service
class SpecLookupService(private val pageFetcher: PageFetcher) {

    private val logger = LoggerFactory.getLogger(SpecLookupService::class.java)

    /** Look up the title for a spec request, or null if the spec does not exist */
    fun lookup(request: SpecRequest): String? {
        return lookupUrl(request.url, request.type)
    }

    /** Fetch a spec page by URL and extract its title */
    fun lookupUrl(url: String, type: SpecType): String? {
        val body = pageFetcher.fetch(url) ?: return null
        return extractTitle(body, type)
    }

    private fun extractTitle(html: String, type: SpecType): String? {
        return try {
            val document = Jsoup.parse(html)
            val element = document.selectFirst(type.cssSelector) ?: return null
            val raw = element.text().trim()
            if (raw.isBlank()) return null
            cleanTitle(raw, type)
        } catch (e: Exception) {
            logger.debug("Failed to extract title: {}", e.message)
            null
        }
    }

    /** Strip common prefixes that duplicate the spec identifier */
    private fun cleanTitle(raw: String, type: SpecType): String {
        return when (type) {
            SpecType.RFC -> raw.removePrefix("RFC ").replace(Regex("^\\d+\\s*-\\s*"), "").trim()
            SpecType.JEP -> raw.removePrefix("JEP ").replace(Regex("^\\d+:\\s*"), "").trim()
            SpecType.JSR -> raw.removePrefix("JSR ").replace(Regex("^\\d+:\\s*"), "").trim()
            SpecType.PEP ->
                raw.removePrefix("PEP ").replace(Regex("^\\d+\\s*\\p{Pd}\\s*"), "").trim()
        }.replace("TM", "")
    }
}
