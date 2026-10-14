/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary

/** A dictionaryapi.dev response; [credited] includes the license and source fields it sends */
fun dictionaryJson(
    word: String,
    partOfSpeech: String,
    definition: String,
    credited: Boolean = true,
): String {
    val credit =
        if (credited)
            """
                "license": {
                  "name": "CC BY-SA 3.0",
                  "url": "https://creativecommons.org/licenses/by-sa/3.0"
                },
                "sourceUrls": ["https://en.wiktionary.org/wiki/$word"],
            """
        else ""
    return """
            [
              {
                "word": "$word",
                "phonetic": "/test/",
                $credit
                "meanings": [
                  {
                    "partOfSpeech": "$partOfSpeech",
                    "definitions": [
                      {
                        "definition": "$definition",
                        "synonyms": [],
                        "antonyms": []
                      }
                    ]
                  }
                ]
              }
            ]
            """
        .trimIndent()
}

/** A FreeDictionaryAPI.com response; no [definition] is its answer for an unknown word */
fun freeDictionaryJson(
    word: String,
    partOfSpeech: String = "",
    definition: String? = null,
): String {
    val entries =
        if (definition == null) ""
        else
            """{"language":{"code":"en","name":"English"},"partOfSpeech":"$partOfSpeech",
               "senses":[{"definition":"$definition","examples":[]}]}"""
    val url =
        if (definition == null) "https://en.wiktionary.org"
        else "https://en.wiktionary.org/wiki/$word"
    return """{"word":"$word","entries":[$entries],
              "source":{"url":"$url","license":{"name":"CC BY-SA 4.0",
              "url":"https://creativecommons.org/licenses/by-sa/4.0/"}}}"""
}
