/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.signal

import dev.streampack.moderation.config.ModerationProperties
import dev.streampack.moderation.model.Signal

/**
 * Scores one line on its own, with no model: what it says and who it's aimed at. Repetition and
 * floods need the lines before it, so [ModerationScores] adds those.
 *
 * The point is behaviour, not vocabulary. Swearing at a build scores almost nothing; the same words
 * aimed at a person ("you …", or alongside another person's name) score far more. A slur or a
 * threat counts heavily on its own.
 */
class SignalScorer(private val properties: ModerationProperties) {
    private val profanity = WordList(properties.profanity)
    private val insults = WordList(properties.insults)
    private val slurs = WordList(properties.slurs)
    private val blockedHosts = properties.blockedHosts.map { it.trim().lowercase() }.toSet()
    private val weights = properties.weights

    /**
     * The signals in [content], said by [sender]. [others] are the nicks of other people seen in
     * the channel lately (lowercase), so a line naming one of them counts as aimed at them.
     */
    fun score(content: String, sender: String, others: Set<String>): Map<Signal, Double> {
        val text = content.lowercase().replace('’', '\'')
        val words = words(text)
        val mentions = mentions(text, words, sender.lowercase(), others)
        val signals = linkedMapOf<Signal, Double>()

        if (words.any { slurs.matches(it.folded) }) signals[Signal.SLUR] = weights.slur
        if (threatens(text, words, mentions)) signals[Signal.THREAT] = weights.threat

        val hostile =
            words.withIndex().filter { (_, w) ->
                profanity.matches(w.folded) || insults.matches(w.folded)
            }
        if (hostile.isNotEmpty()) {
            val aimed =
                mentions.isNotEmpty() ||
                    hostile.any { (i, _) ->
                        words.withIndex().any { (j, w) ->
                            w.folded in SECOND_PERSON && kotlin.math.abs(i - j) <= AIM_DISTANCE
                        }
                    }
            when {
                aimed -> signals[Signal.AIMED_HOSTILITY] = weights.aimedHostility
                hostile.any { (_, w) -> insults.matches(w.folded) } ->
                    signals[Signal.INSULT] = weights.insult
                else -> signals[Signal.PROFANITY] = weights.profanity
            }
        }

        if (personalDetails(text)) {
            signals[Signal.PERSONAL_DETAILS] =
                if (mentions.isNotEmpty()) weights.personalDetailsAboutSomeone
                else weights.personalDetails
        }
        if (blockedHosts.isNotEmpty() && links(text).any(::blocked)) {
            signals[Signal.BLOCKED_LINK] = weights.blockedLink
        }
        return signals
    }

    /** A word as written (lowercase) and folded for matching. */
    data class Word(val raw: String, val folded: String)

    private fun words(text: String): List<Word> =
        WORD.findAll(text)
            .map { it.value }
            .map { Word(it.trim('\'', '.'), fold(it)) }
            .filter { it.folded.isNotEmpty() }
            .toList()

    /**
     * Who a line names, other than its speaker: `@name` anywhere (Slack, Mattermost and Discord
     * mentions all carry the `@`), and any word that's the nick of someone else seen in the channel
     * lately, which covers IRC's `nick: …`.
     */
    private fun mentions(
        text: String,
        words: List<Word>,
        sender: String,
        others: Set<String>,
    ): Set<String> {
        val named = mutableSetOf<String>()
        AT_MENTION.findAll(text).forEach { named += it.groupValues[1] }
        words.forEach { if (it.raw in others) named += it.raw }
        named -= sender
        return named
    }

    private fun threatens(text: String, words: List<Word>, mentions: Set<String>): Boolean {
        if (SELF_HARM.containsMatchIn(text)) return true
        return THREAT.findAll(text).any { match ->
            val target = match.groupValues[THREAT_TARGET].trim('@', ',', '.', '!', '?')
            target in THREAT_TARGETS || target in mentions
        } || (words.any { it.folded == "kys" })
    }

    private fun personalDetails(text: String): Boolean =
        EMAIL.containsMatchIn(text) ||
            PHONE.containsMatchIn(text) ||
            STREET_ADDRESS.containsMatchIn(text)

    private fun links(text: String): List<String> =
        LINK.findAll(text).map { it.groupValues[1] }.toList()

    private fun blocked(host: String): Boolean = blockedHosts.any {
        host == it || host.endsWith(".$it")
    }

    /** A word list: `stem*` matches words starting with the stem, others the word or its plural. */
    class WordList(entries: List<String>) {
        private val stems = entries.filter { it.endsWith("*") }.map { fold(it.dropLast(1)) }
        private val exact = entries.filterNot { it.endsWith("*") }.map { fold(it) }.toSet()
        private val collapsedStems = stems.map(::collapse)
        private val collapsedExact = exact.map(::collapse).toSet()

        fun matches(word: String): Boolean =
            matchesIn(word, stems, exact) ||
                // "fuuuuck" and "idiooot": letters drawn out still count. Only then, so "piston"
                // isn't read as "piss" with its letters run together.
                (DRAWN_OUT.containsMatchIn(word) &&
                    matchesIn(collapse(word), collapsedStems, collapsedExact))

        private fun matchesIn(word: String, stems: List<String>, exact: Set<String>): Boolean =
            word in exact ||
                (word.endsWith("s") && word.dropLast(1) in exact) ||
                (word.endsWith("es") && word.dropLast(2) in exact) ||
                stems.any { word.startsWith(it) }
    }

    companion object {
        /** How many words apart a hostile word and a "you" may be and still go together. */
        private const val AIM_DISTANCE = 3

        private val SECOND_PERSON =
            setOf(
                "you",
                "your",
                "youre",
                "yours",
                "yourself",
                "yourselves",
                "ur",
                "u",
                "ya",
                "yall",
            )
        /** Whom a threat is made against: "I'll find your bug" isn't one. */
        private val THREAT_TARGETS = setOf("you", "u", "ya", "yall", "him", "her", "them")
        private val DRAWN_OUT = Regex("(\\p{L})\\1\\1")

        private val WORD = Regex("[\\p{L}\\p{N}@$'*]+")
        private val AT_MENTION = Regex("(?:^|[\\s<(])@([\\p{L}\\p{N}_.\\-\\[\\]`|^{}]{2,})")
        private val SELF_HARM =
            Regex("\\b(kill|hang|neck) (yo)?ur ?self\\b|\\bkill yourselves\\b|\\bgo die\\b")
        private const val THREAT_TARGET = 4
        private val THREAT =
            Regex(
                "\\b(i'?ll|i will|i'?m going to|i'?m gonna|im gonna|gonna|going to)\\s+" +
                    "((?:\\S+\\s+){0,2}?)" +
                    "(kill|hurt|stab|shoot|murder|rape|strangle|choke|punch|smash)" +
                    "\\s+(\\S+)"
            )
        private val EMAIL = Regex("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)*\\.[a-z]{2,}")
        private val PHONE =
            Regex(
                "(?<![\\w.])(\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}|\\+\\d{1,3}[ .-]?\\d[\\d .-]{7,}\\d)" +
                    "(?![\\w.])"
            )
        private val STREET_ADDRESS =
            Regex(
                "\\b\\d{1,5}\\s+(?:[a-z]+\\s+){1,3}" +
                    "(street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|court)\\b"
            )
        private val LINK = Regex("https?://([^/\\s:?#]+)")

        /**
         * Lowercase letters only, with the usual stand-ins (`1` for `i`, `$` for `s`) put back in a
         * word that has letters at all: a plain number stays out of it.
         */
        fun fold(word: String): String {
            if (word.none { it.isLetter() }) return ""
            return word.lowercase().map { LEET[it] ?: it }.filter { it.isLetter() }.joinToString("")
        }

        /** Runs of a letter as one: "fuuuck" is "fuck", and so is "fuck". */
        fun collapse(word: String): String =
            word
                .fold(StringBuilder()) { b, c ->
                    if (b.isEmpty() || b.last() != c) b.append(c) else b
                }
                .toString()

        private val LEET =
            mapOf(
                '0' to 'o',
                '1' to 'i',
                '3' to 'e',
                '4' to 'a',
                '5' to 's',
                '7' to 't',
                '@' to 'a',
            ) + mapOf('$' to 's')
    }
}
