/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import dev.streampack.core.json.JacksonMappers
import dev.streampack.dictionary.model.DictionaryResult
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * dictionaryapi.dev: Wiktionary's text. Its origin went down on 2026-10-12 (#202), answering only
 * what Cloudflare had cached; it stays in the list, after FreeDictionaryAPI.com, for when it comes
 * back. An unknown word is a 404.
 */
@Component
class DictionaryApiDevProvider(
    private val http: DictionaryHttp,
    @Value(
        "\${streampack.dictionary.dictionaryapi-dev-url:https://api.dictionaryapi.dev/api/v2/entries/en}",
    )
    private val baseUrl: String,
) : DictionaryProvider {
    override val name = "dictionaryapi.dev"
    override val order = 20
    private val mapper = JacksonMappers.standard()

    override fun lookup(word: String): DictionaryAnswer {
        val response = http.get("$baseUrl/${DictionaryHttp.segment(word)}")
        if (response.status == 404) return DictionaryAnswer.Unknown
        if (response.status != 200) return DictionaryAnswer.Failed("HTTP ${response.status}")
        val tree = mapper.readTree(response.body)
        if (!tree.isArray) return DictionaryAnswer.Failed("not a list of entries")
        if (tree.isEmpty) return DictionaryAnswer.Unknown
        val entry = tree[0]
        val meaning = entry.path("meanings").path(0)
        val definition =
            meaning.path("definitions").path(0).path("definition").asString(null)?.takeIf {
                it.isNotBlank()
            } ?: return DictionaryAnswer.Failed("no definition in the entry")
        // Wiktionary's text is CC BY-SA, so every result carries its page and license
        val url = entry.path("sourceUrls").path(0).asString(null) ?: wiktionaryUrl(word)
        val license = entry.path("license").path("name").asString(null) ?: "CC BY-SA"
        return DictionaryAnswer.Found(
            DictionaryResult(
                word = entry.path("word").asString(word),
                partOfSpeech = meaning.path("partOfSpeech").asString(""),
                definition = definition,
                credit = "(Wiktionary via dictionaryapi.dev, $license: $url)",
            ),
        )
    }
}
