/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import clojure.java.api.Clojure
import clojure.lang.IFn
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Kotlin shim for Clojure excerpt summarization. */
@Service
class ExcerptSummarizerService {
    private val logger = LoggerFactory.getLogger(ExcerptSummarizerService::class.java)
    private val summarizeFn: IFn

    init {
        val require = Clojure.`var`("clojure.core", "require")
        require.invoke(Clojure.read("dev.streampack.blog.summary"))
        summarizeFn = Clojure.`var`("dev.streampack.blog.summary", "summarize")
        logger.info("Clojure blog summary namespace loaded")
    }

    fun summarize(text: String, maxSentences: Int = 3): String {
        if (text.isBlank()) return ""
        return (summarizeFn.invoke(text, maxSentences) as? String)?.trim().orEmpty()
    }
}
