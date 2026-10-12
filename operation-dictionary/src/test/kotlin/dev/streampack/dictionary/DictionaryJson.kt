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
