/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.signal

import dev.streampack.moderation.config.ModerationProperties
import dev.streampack.moderation.model.Signal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow
import org.springframework.stereotype.Component

/**
 * Each person's score in each channel, in memory (#150). Every line adds what [SignalScorer] finds
 * in it, plus repetition and flood signals from the lines before it; the score halves every
 * [ModerationProperties.halfLife] with nothing new. When a line takes a score to the threshold, the
 * person is marked for the next review, which takes the mark (and what raised it) away.
 *
 * Scores are never shown to anyone, and a restart forgets them: they're a pointer for the review,
 * not a record. Reports are the record.
 */
@Component
class ModerationScores(private val properties: ModerationProperties) {
    private val scorer = SignalScorer(properties)
    private val tallies = ConcurrentHashMap<Key, Tally>()
    private val speakers = ConcurrentHashMap<String, ConcurrentHashMap<String, Instant>>()

    /** A person in a channel: the provenance URI as logged, and the sender as logged. */
    data class Key(val provenanceUri: String, val sender: String)

    /** Who said it, as the message log has them. */
    data class Speaker(
        val provenanceUri: String,
        val sender: String,
        val protocol: String,
        val serviceId: String?,
        val userId: UUID?,
    )

    /** What one line added, and where the score stands after it. */
    data class Scored(val signals: Map<Signal, Double>, val score: Double, val marked: Boolean)

    /** A person marked for review, with what raised them since the last one. */
    data class Candidate(
        val speaker: Speaker,
        val score: Double,
        val peak: Double,
        val markedAt: Instant,
        val firstSignalAt: Instant,
        val signals: Map<Signal, Int>,
        /**
         * The lines that raised a signal, as said, with what each added, so a review can find them
         * in the log and send the worst first.
         */
        val signalLines: Map<String, Double>,
    )

    private class Tally(var speaker: Speaker) {
        var score = 0.0
        var at: Instant = Instant.EPOCH
        val recent = ArrayDeque<Pair<Instant, String>>()
        var markedAt: Instant? = null
        var firstSignalAt: Instant? = null
        var peak = 0.0
        val signals = linkedMapOf<Signal, Int>()
        val signalLines = linkedMapOf<String, Double>()
    }

    /** Scores one line [speaker] said at [now]. */
    fun record(speaker: Speaker, content: String, now: Instant = Instant.now()): Scored {
        val sender = speaker.sender.lowercase()
        val channelSpeakers =
            speakers.computeIfAbsent(speaker.provenanceUri) { ConcurrentHashMap() }
        val others =
            channelSpeakers
                .filter { (nick, seen) -> nick != sender && !expired(seen, now) }
                .keys
                .toSet()
        channelSpeakers[sender] = now

        val signals = scorer.score(content, speaker.sender, others).toMutableMap()
        val tally = tallies.computeIfAbsent(Key(speaker.provenanceUri, sender)) { Tally(speaker) }
        synchronized(tally) {
            tally.speaker = speaker
            decay(tally, now)
            while (tally.recent.isNotEmpty() && expired(tally.recent.first().first, now)) {
                tally.recent.removeFirst()
            }
            val normalized = normalize(content)
            if (normalized.isNotEmpty()) {
                val copies = tally.recent.count { it.second == normalized }
                if (copies > 0) signals[Signal.REPETITION] = properties.weights.repetition * copies
            }
            val floodStart = now.minus(properties.floodWindow)
            val burst = tally.recent.count { it.first > floodStart } + 1
            if (burst > properties.floodLines) signals[Signal.FLOOD] = properties.weights.flood
            tally.recent.addLast(now to normalized)
            while (tally.recent.size > MAX_RECENT) tally.recent.removeFirst()

            tally.score += signals.values.sum()
            if (signals.isNotEmpty()) {
                if (tally.firstSignalAt == null) tally.firstSignalAt = now
                signals.keys.forEach { tally.signals.merge(it, 1, Int::plus) }
                keepSignalLine(tally, content, signals.values.sum())
            }
            tally.peak = maxOf(tally.peak, tally.score)
            if (signals.isNotEmpty() && tally.score >= properties.threshold) {
                if (tally.markedAt == null) tally.markedAt = now
            }
            return Scored(signals, tally.score, tally.markedAt != null)
        }
    }

    /**
     * Remembers [content] as a line that added [weight]. When the list is full, the weakest line
     * makes way for a stronger one: a slur after a hundred flood lines is still the line to show.
     */
    private fun keepSignalLine(tally: Tally, content: String, weight: Double) {
        val lines = tally.signalLines
        if (content in lines || lines.size < MAX_SIGNAL_LINES) {
            lines.merge(content, weight, ::maxOf)
            return
        }
        val weakest = lines.minBy { it.value }
        if (weakest.value < weight) {
            lines.remove(weakest.key)
            lines[content] = weight
        }
    }

    /** Where a person's score stands at [now]: for tests and the review, never shown to anyone. */
    fun scoreOf(provenanceUri: String, sender: String, now: Instant = Instant.now()): Double {
        val tally = tallies[Key(provenanceUri, sender.lowercase())] ?: return 0.0
        synchronized(tally) {
            decay(tally, now)
            return tally.score
        }
    }

    /**
     * The people marked since the last review, each taken off the list as it's returned: a person
     * is reviewed again only if new lines take them to the threshold again. Tallies with nothing
     * left to remember are dropped along the way.
     */
    fun takeForReview(now: Instant = Instant.now()): List<Candidate> {
        val taken = mutableListOf<Candidate>()
        for ((key, tally) in tallies) {
            synchronized(tally) {
                decay(tally, now)
                val marked = tally.markedAt
                if (marked != null) {
                    taken +=
                        Candidate(
                            speaker = tally.speaker,
                            score = tally.score,
                            peak = tally.peak,
                            markedAt = marked,
                            firstSignalAt = tally.firstSignalAt ?: marked,
                            signals = tally.signals.toMap(),
                            signalLines = tally.signalLines.toMap(),
                        )
                }
                if (marked != null || tally.firstSignalAt?.let { expired(it, now) } == true) {
                    tally.markedAt = null
                    tally.firstSignalAt = null
                    tally.peak = tally.score
                    tally.signals.clear()
                    tally.signalLines.clear()
                }
                if (tally.score < FORGET_BELOW && tally.recent.all { expired(it.first, now) }) {
                    tallies.remove(key, tally)
                }
            }
        }
        speakers.values.forEach { channel -> channel.entries.removeIf { expired(it.value, now) } }
        speakers.entries.removeIf { it.value.isEmpty() }
        return taken
    }

    /** Forgets everything; for tests. */
    fun clear() {
        tallies.clear()
        speakers.clear()
    }

    private fun decay(tally: Tally, now: Instant) {
        val elapsed = Duration.between(tally.at, now)
        if (tally.at != Instant.EPOCH && !elapsed.isNegative && !elapsed.isZero) {
            val halves = elapsed.toMillis().toDouble() / properties.halfLife.toMillis()
            tally.score *= 0.5.pow(halves)
        }
        if (now > tally.at) tally.at = now
    }

    private fun expired(at: Instant, now: Instant) = at < now.minus(properties.window)

    companion object {
        private const val MAX_RECENT = 200
        private const val MAX_SIGNAL_LINES = 100
        private const val FORGET_BELOW = 0.05

        /** The same line, near enough: case, spacing and punctuation don't make it new. */
        fun normalize(content: String): String =
            content
                .lowercase()
                .filter { it.isLetterOrDigit() || it.isWhitespace() }
                .trim()
                .let {
                    it.replace(Regex("\\s+"), " ")
                }
    }
}
