/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary

fun dictionaryJson(word: String, partOfSpeech: String, definition: String): String =
    """
            [
              {
                "word": "$word",
                "phonetic": "/test/",
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
