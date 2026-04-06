/* Joseph B. Ottinger (C)2026 */
package dev.streampack.karma.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Provenance
import dev.streampack.core.service.OperationConfigService
import dev.streampack.core.service.TypedOperation
import dev.streampack.karma.config.KarmaProperties
import dev.streampack.karma.service.KarmaService
import java.util.regex.Pattern
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/** Handles karma increment/decrement: "foo++", "bar--", "c++--" */
@Component
class SetKarmaOperation(
    private val karmaService: KarmaService,
    private val karmaProperties: KarmaProperties,
    private val operationConfigService: OperationConfigService,
) : TypedOperation<String>(String::class) {

    override val priority: Int = 40
    override val addressed: Boolean = false
    override val operationGroup: String = "karma"

    /** Reads ignoreEmdash from per-provenance operation config (defaults to true) */
    private fun shouldIgnoreEmdash(message: Message<*>): Boolean {
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val provenanceUri = provenance?.encode() ?: ""
        val config = operationConfigService.getOperationConfig(provenanceUri, "karma")
        return config["ignoreEmdash"]?.toString()?.toBoolean() ?: true
    }

    /**
     * Preprocessing pipeline: completion collapse, arrow fix, conditional prose dash neutralization
     */
    private fun String.preprocess(ignoreEmdash: Boolean): String {
        var result =
            COMPLETION_SPACING.matcher(this)
                .replaceAll("$1$2")
                .replace("-->", "->")
                .replace("<--", "<-")
        if (ignoreEmdash) {
            result = result.replace(" -- ", " - ")
        }
        return result
    }

    override fun canHandle(payload: String, message: Message<*>): Boolean {
        val fixed = payload.preprocess(shouldIgnoreEmdash(message))
        val matcher = KARMA_PATTERN.matcher(fixed)
        if (!matcher.find()) return false
        val subject = matcher.group(1).stripCompletionSuffix()
        return subject.isNotEmpty() &&
            subject.length <= karmaProperties.maxSubjectLength &&
            !isLanguageReference(subject)
    }

    override fun handle(payload: String, message: Message<*>): OperationOutcome? {
        val fixed = payload.preprocess(shouldIgnoreEmdash(message))
        val matcher = KARMA_PATTERN.matcher(fixed)
        if (!matcher.find()) return null

        val subject = matcher.group(1).stripCompletionSuffix()
        if (
            subject.isEmpty() ||
                subject.length > karmaProperties.maxSubjectLength ||
                isLanguageReference(subject)
        ) {
            return null
        }

        val predicate = matcher.group(2)
        var increment = if (predicate == "++") 1 else -1

        // Check immune subjects
        if (karmaProperties.immuneSubjects.any { it.equals(subject, ignoreCase = true) }) {
            logger.debug("Ignoring karma change for immune subject: {}", subject)
            return null
        }

        // Resolve sender identity for self-karma check
        val provenance = message.headers[Provenance.HEADER] as? Provenance
        val senderNick = message.headers["nick"] as? String ?: provenance?.user?.username

        val selfKarma = senderNick != null && senderNick.equals(subject, ignoreCase = true)
        if (selfKarma && increment > 0) {
            increment = -1
        }

        val karma = karmaService.adjustKarma(subject, increment)

        return if (selfKarma) {
            val prefix =
                if (predicate == "++") {
                    "You can't increment your own karma! "
                } else {
                    ""
                }
            OperationResult.Success("${prefix}Your karma is now $karma.")
        } else {
            if (karma == 0) {
                OperationResult.Success("$subject has neutral karma.")
            } else {
                OperationResult.Success("$subject now has karma of $karma.")
            }
        }
    }

    companion object {
        /** Greedy subject, ++ or --, negative lookahead prevents matching flags like --verbose */
        private val KARMA_PATTERN: Pattern = Pattern.compile("^(.+)(\\+{2}|--)(?!\\w).*\$")

        /** Collapse nick-completion spacing so "jreicher: ++" becomes "jreicher:++" */
        private val COMPLETION_SPACING: Pattern = Pattern.compile("([;:,])\\s+(\\+{2}|--)")

        /**
         * Language names that cause false positives when used as last token in multi-word subjects
         */
        private val LANGUAGE_SUFFIXES = setOf("c", "j")

        /** Strip IRC nick-completion suffixes (colon, comma, semicolon) */
        private fun String.stripCompletionSuffix() = this.trim().trimEnd(':', ',', ';').trim()

        /** Reject multi-token subjects whose last token is a single-letter language name */
        private fun isLanguageReference(subject: String): Boolean {
            val tokens = subject.trim().split("\\s+".toRegex())
            if (tokens.size < 2) return false
            return tokens.last().lowercase() in LANGUAGE_SUFFIXES
        }
    }
}
