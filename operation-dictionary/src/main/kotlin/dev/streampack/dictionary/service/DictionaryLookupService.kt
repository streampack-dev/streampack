/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.service.PageFetcher
import dev.streampack.dictionary.model.DictionaryResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Fetches word definitions from the Free Dictionary API */
@Service
open class DictionaryLookupService(private val pageFetcher: PageFetcher) {

    private val logger = LoggerFactory.getLogger(DictionaryLookupService::class.java)
    private val mapper = JacksonMappers.standard()

    /** Look up the first definition of a word, or null if the word is not found */
    open fun lookup(word: String): DictionaryResult? {
        return lookupUrl(buildUrl(word))
    }

    /** Fetch a dictionary page by URL and extract the first definition */
    fun lookupUrl(url: String): DictionaryResult? {
        val body = pageFetcher.fetch(url) ?: return null
        return parseDefinition(body)
    }

    /** Build the API URL for a word lookup */
    protected open fun buildUrl(word: String): String {
        return "https://api.dictionaryapi.dev/api/v2/entries/en/$word"
    }

    private fun parseDefinition(json: String): DictionaryResult? {
        return try {
            val tree = mapper.readTree(json)
            if (!tree.isArray || tree.isEmpty) return null

            val entry = tree[0]
            val word = entry.path("word").asString(null) ?: return null
            val meanings = entry.path("meanings")
            if (!meanings.isArray || meanings.isEmpty) return null

            val firstMeaning = meanings[0]
            val partOfSpeech = firstMeaning.path("partOfSpeech").asString(null) ?: return null
            val definitions = firstMeaning.path("definitions")
            if (!definitions.isArray || definitions.isEmpty) return null

            val definition = definitions[0].path("definition").asString(null) ?: return null
            if (definition.isBlank()) return null

            DictionaryResult(word, partOfSpeech, definition)
        } catch (e: Exception) {
            logger.debug("Failed to parse dictionary response: {}", e.message)
            null
        }
    }
}
