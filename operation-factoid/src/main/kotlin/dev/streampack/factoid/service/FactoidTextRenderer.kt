/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.service

import dev.streampack.factoid.model.FactoidAttributeType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/** Resolves (a|b|c) selection groups and ~factoid references in factoid text values */
@Service
class FactoidTextRenderer(private val factoidService: FactoidService) {
    private val logger = LoggerFactory.getLogger(FactoidTextRenderer::class.java)

    /**
     * Replaces all (x|y|z) groups with a randomly chosen element. Supports nested parentheses: (Ask
     * me( tomorrow| again)|No) resolves the inner group after picking "Ask me(...)". Empty options
     * are valid: (a|) means "a" or nothing.
     */
    fun resolveSelections(value: String, hopsRemaining: Int): String {
        if (hopsRemaining <= 0) return value
        val result = StringBuilder()
        var i = 0
        while (i < value.length) {
            if (value[i] == '(') {
                val group = extractBalancedGroup(value, i)
                if (group != null) {
                    val (content, endIndex) = group
                    val options = splitTopLevel(content)
                    if (options.size > 1) {
                        val chosen = options.random()
                        // Recursively resolve nested groups, then handle ~ references
                        val nested = resolveSelections(chosen, hopsRemaining)
                        result.append(resolveElement(nested, hopsRemaining))
                    } else {
                        // No top-level pipe - parenthesized text, pass through unchanged
                        result.append('(')
                        result.append(content)
                        result.append(')')
                    }
                    i = endIndex + 1
                } else {
                    // Unbalanced paren, pass through as literal
                    result.append(value[i])
                    i++
                }
            } else {
                result.append(value[i])
                i++
            }
        }
        return result.toString()
    }

    /** Finds the matching close paren for an open paren at the given position */
    private fun extractBalancedGroup(value: String, start: Int): Pair<String, Int>? {
        var depth = 0
        for (i in start until value.length) {
            when (value[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        return Pair(value.substring(start + 1, i), i)
                    }
                }
            }
        }
        return null
    }

    /** Splits content by | at depth 0 only, preserving nested groups intact */
    private fun splitTopLevel(content: String): List<String> {
        val options = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in content.indices) {
            when (content[i]) {
                '(' -> depth++
                ')' -> depth--
                '|' ->
                    if (depth == 0) {
                        options.add(content.substring(start, i))
                        start = i + 1
                    }
            }
        }
        options.add(content.substring(start))
        return options
    }

    /** Resolves a single chosen element: strips ~ prefix and looks up the factoid if present */
    private fun resolveElement(element: String, hopsRemaining: Int): String {
        if (!element.startsWith("~")) return element
        val selector = element.removePrefix("~").trim()
        return resolveReference(selector, hopsRemaining - 1) ?: ""
    }

    /** Looks up a factoid by selector and returns its resolved TEXT value, or null on miss */
    fun resolveReference(selector: String, hopsRemaining: Int): String? {
        if (hopsRemaining <= 0) return null
        val attributes = factoidService.findBySelector(selector)
        val textAttr =
            attributes.firstOrNull { it.attributeType == FactoidAttributeType.TEXT } ?: return null
        val raw = textAttr.attributeValue ?: return null

        // Strip <reply> prefix if present, matching TEXT rendering behavior
        val stripped =
            if (raw.startsWith("<reply>", true)) {
                raw.substring("<reply>".length)
            } else {
                raw
            }

        // Recursively resolve any selection groups in the referenced factoid
        return resolveSelections(stripped, hopsRemaining - 1)
    }
}
