/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import dev.streampack.core.json.JacksonMappers
import dev.streampack.dictionary.model.DictionaryResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * FreeDictionaryAPI.com: Wiktionary's text, 1,000 requests an hour per address. Its terms want a
 * visible credit to it and a link to the Wiktionary page, so its credit carries both. An unknown
 * word is a 200 with no entries.
 */
@Component
class FreeDictionaryApiProvider(
    private val http: DictionaryHttp,
    @Value(
        "\${streampack.dictionary.free-dictionary-url:https://freedictionaryapi.com/api/v1/entries/en}",
    )
    private val baseUrl: String,
) : DictionaryProvider {
    override val name = "FreeDictionaryAPI.com"
    override val order = 10
    private val mapper = JacksonMappers.standard()

    override fun lookup(word: String): DictionaryAnswer {
        val response = http.get("$baseUrl/${DictionaryHttp.segment(word)}")
        if (response.status == 404) return DictionaryAnswer.Unknown
        if (response.status != 200) return DictionaryAnswer.Failed("HTTP ${response.status}")
        val tree = mapper.readTree(response.body)
        val entries = tree.path("entries")
        if (!entries.isArray) return DictionaryAnswer.Failed("no entries in the answer")
        val entry =
            entries.firstOrNull { it.path("senses").path(0).path("definition").isString }
                ?: return DictionaryAnswer.Unknown
        val definition = entry.path("senses").path(0).path("definition").asString()
        val partOfSpeech = entry.path("partOfSpeech").asString("")
        val source = tree.path("source")
        // Its page links can carry the phrase's space, which would end the link in a chat line
        val url = source.path("url").asString(null)?.replace(' ', '_') ?: wiktionaryUrl(word)
        val license = source.path("license").path("name").asString(null) ?: "CC BY-SA 4.0"
        return DictionaryAnswer.Found(
            DictionaryResult(
                word = tree.path("word").asString(word),
                partOfSpeech = partOfSpeech,
                definition = definition,
                credit = "(Wiktionary via FreeDictionaryAPI.com, $license: $url)",
            ),
        )
    }
}

/** The Wiktionary page for [word], for a source that doesn't name its own */
internal fun wiktionaryUrl(word: String): String =
    "https://en.wiktionary.org/wiki/" + DictionaryHttp.segment(word.replace(' ', '_'))
