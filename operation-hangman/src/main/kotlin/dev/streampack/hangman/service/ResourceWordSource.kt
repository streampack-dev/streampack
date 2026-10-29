/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.stereotype.Component

/**
 * Loads hangman words, one to a line, from `streampack.hangman.words`: a resource location such as
 * `file:/opt/streampack/ext/words/hangman.txt`. The default is the list that ships in this jar.
 */
@Component
class ResourceWordSource(@Value("\${streampack.hangman.words:$DEFAULT}") location: String) :
    WordSource {
    private val logger = LoggerFactory.getLogger(ResourceWordSource::class.java)
    private val words: List<String>

    init {
        val resource = DefaultResourceLoader(javaClass.classLoader).getResource(location)
        check(resource.exists()) { "No hangman word list at $location" }
        words =
            resource.inputStream
                .bufferedReader()
                .readLines()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
        check(words.isNotEmpty()) { "The hangman word list at $location has no words" }
        logger.info("Loaded {} hangman words from {}", words.size, location)
    }

    override fun randomWord(): String = words.random()

    companion object {
        const val DEFAULT = "classpath:hangman-words.txt"
    }
}
