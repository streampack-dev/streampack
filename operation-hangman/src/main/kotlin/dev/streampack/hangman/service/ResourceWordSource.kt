/* Joseph B. Ottinger (C)2026 */
package dev.streampack.hangman.service

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnResource
import org.springframework.stereotype.Component

/** Loads hangman words from a classpath resource file */
@Component
@ConditionalOnResource(resources = ["classpath:hangman-words.txt"])
class ResourceWordSource : WordSource {
    private val logger = LoggerFactory.getLogger(ResourceWordSource::class.java)
    private val words: List<String>

    init {
        words =
            javaClass.classLoader
                .getResourceAsStream("hangman-words.txt")!!
                .bufferedReader()
                .readLines()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
        logger.info("Loaded {} hangman words from classpath resource", words.size)
    }

    override fun randomWord(): String = words.random()
}
