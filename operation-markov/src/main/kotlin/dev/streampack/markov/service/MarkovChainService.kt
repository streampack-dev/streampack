/* Joseph B. Ottinger (C)2026 */
package dev.streampack.markov.service

import clojure.java.api.Clojure
import clojure.lang.IFn
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Kotlin shim over the Clojure Markov chain implementation. Loads the Clojure namespace once at
 * init, then delegates chain building and generation to pure Clojure functions.
 */
@Service
class MarkovChainService {
    private val logger = LoggerFactory.getLogger(MarkovChainService::class.java)
    private val generateFn: IFn

    init {
        val require = Clojure.`var`("clojure.core", "require")
        require.invoke(Clojure.read("dev.streampack.markov.chain"))
        generateFn = Clojure.`var`("dev.streampack.markov.chain", "generate")
        logger.info("Clojure Markov chain namespace loaded")
    }

    /**
     * Generates a sentence from a corpus of messages. Returns null if the corpus is too small to
     * produce output.
     */
    fun generate(messages: List<String>, maxWords: Int = 30): String? {
        return generateFn.invoke(messages, maxWords) as? String
    }
}
