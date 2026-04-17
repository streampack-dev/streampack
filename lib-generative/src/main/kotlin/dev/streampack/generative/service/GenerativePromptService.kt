/* Joseph B. Ottinger (C)2026 */
package dev.streampack.generative.service

import clojure.java.api.Clojure
import clojure.lang.IFn
import clojure.lang.Keyword
import clojure.lang.PersistentArrayMap
import dev.streampack.generative.config.GenerativeProperties
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.core.io.ResourceLoader

/**
 * Resolves generative prompt text for consumer modules.
 *
 * This service supports a three-tier prompt lookup model:
 *
 * 1. runtime filesystem `.clj` prompt
 * 2. runtime filesystem `.txt` prompt
 * 3. bundled classpath fallback prompt
 *
 * The service is intentionally consumer-agnostic. Each caller provides:
 *
 * - a prompt name such as `suggest-prompt`
 * - a classpath fallback resource path
 * - a context map for dynamic prompt generation
 *
 * ## Filesystem Behavior
 *
 * If `streampack.generative.prompt-dir` is configured, prompt resolution checks that directory
 * first. This allows operators to tune prompts without rebuilding the application image.
 *
 * ## Clojure Behavior
 *
 * If a `.clj` prompt file is present, it must evaluate to a single function. That function is
 * invoked on every call to [render] with a Clojure map whose keys are keywords derived from the
 * Kotlin context map.
 *
 * Example Kotlin:
 *
 * `mapOf("sourceTitle" to "Signals and Noise")`
 *
 * Example Clojure access:
 *
 * `(:sourceTitle ctx)`
 *
 * ## Failure Semantics
 *
 * External prompt loading failures are non-fatal by design. If an external `.clj` or `.txt`
 * prompt cannot be loaded, the service logs a warning and falls back to the next resolution step.
 * This keeps deployment mistakes from breaking the entire feature path.
 */
class GenerativePromptService(
    private val properties: GenerativeProperties,
    private val resourceLoader: ResourceLoader,
) {
    private val logger = LoggerFactory.getLogger(GenerativePromptService::class.java)
    private val readStringFn: IFn = Clojure.`var`("clojure.core", "read-string")
    private val evalFn: IFn = Clojure.`var`("clojure.core", "eval")

    /**
     * Resolves the final prompt text for a named generative feature.
     *
     * Resolution order is:
     *
     * 1. `${promptDir}/{promptName}.clj`
     * 2. `${promptDir}/{promptName}.txt`
     * 3. `classpath:{fallbackClasspathResource}`
     *
     * When a `.clj` file is present, it is parsed and evaluated as Clojure code and must yield a
     * function of one argument. That function receives the provided [context] as a Clojure map
     * with keyword keys.
     *
     * Example:
     *
     * ```kotlin
     * val systemPrompt =
     *     promptService.render(
     *         "suggest-prompt",
     *         "dev/streampack/ideas/prompts/suggest-prompt.txt",
     *         mapOf("sourceTitle" to title, "extractedText" to extractedText),
     *     )
     * ```
     *
     * @param promptName logical prompt name without file extension
     * @param fallbackClasspathResource classpath resource path used when no usable external prompt
     *   is available
     * @param context dynamic values exposed to a `.clj` prompt function; string keys become
     *   Clojure keyword keys
     * @return the prompt text to pass into the consumer’s AI/generative pipeline
     */
    fun render(
        promptName: String,
        fallbackClasspathResource: String,
        context: Map<String, Any?> = emptyMap(),
    ): String {
        resolveExternalClj(promptName, context)?.let {
            return it
        }
        resolveExternalText(promptName)?.let {
            return it
        }
        return resolveClasspathFallback(fallbackClasspathResource)
    }

    /**
     * Attempts to resolve a dynamic `.clj` prompt override for the given prompt name.
     *
     * The file is expected to evaluate to a single Clojure function that accepts one context map
     * argument and returns prompt text.
     */
    private fun resolveExternalClj(promptName: String, context: Map<String, Any?>): String? {
        val file = resolvePromptPath(promptName, "clj") ?: return null
        return try {
            val source = Files.readString(file)
            val compiled = evalFn.invoke(readStringFn.invoke(source)) as IFn
            compiled.invoke(toClojureContext(context))?.toString()
        } catch (e: Exception) {
            logger.warn("Failed to evaluate Clojure prompt {}: {}", file, e.message)
            null
        }
    }

    /** Attempts to resolve a static `.txt` prompt override for the given prompt name. */
    private fun resolveExternalText(promptName: String): String? {
        val file = resolvePromptPath(promptName, "txt") ?: return null
        return try {
            Files.readString(file)
        } catch (e: Exception) {
            logger.warn("Failed to read prompt override {}: {}", file, e.message)
            null
        }
    }

    /** Reads the bundled fallback prompt from the classpath. */
    private fun resolveClasspathFallback(fallbackClasspathResource: String): String =
        resourceLoader
            .getResource("classpath:$fallbackClasspathResource")
            .inputStream
            .bufferedReader()
            .use { it.readText() }

    /** Resolves a candidate filesystem prompt path if the configured prompt directory is usable. */
    private fun resolvePromptPath(promptName: String, extension: String): Path? {
        val promptDir = properties.promptDir.trim()
        if (promptDir.isBlank()) return null
        val candidate = Path.of(promptDir).resolve("$promptName.$extension")
        return candidate.takeIf { Files.isRegularFile(it) }
    }

    /**
     * Converts a Kotlin string-keyed map into a Clojure map with keyword keys.
     *
     * Null values are omitted so prompt files can treat missing keys as absent rather than present
     * with a null value.
     */
    private fun toClojureContext(context: Map<String, Any?>): PersistentArrayMap {
        val pairs = ArrayList<Any>(context.size * 2)
        context.forEach { (key, value) ->
            value?.let {
                pairs.add(Keyword.intern(key))
                pairs.add(value)
            }
        }
        return PersistentArrayMap.createWithCheck(pairs.toTypedArray())
    }
}
