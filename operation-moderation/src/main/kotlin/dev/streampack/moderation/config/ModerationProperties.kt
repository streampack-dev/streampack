/* Joseph B. Ottinger (C)2026 */
package dev.streampack.moderation.config

import dev.streampack.moderation.signal.ModerationWords
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Abuse detection (#150): cheap signals on every public channel message, an hourly review of the
 * few people they point at, and reports for an admin to act on. Nothing here hides, removes or bans
 * anything on its own.
 *
 * The word lists are short on purpose: words alone count for little, and what matters is hostility
 * aimed at people, again and again. An entry ending in `*` matches any word it starts (`fuck*`
 * matches `fucking`); any other entry matches the word itself, or the word with an `s`.
 */
@ConfigurationProperties(prefix = "streampack.moderation")
data class ModerationProperties(
    /** Score messages and run the hourly review at all. */
    val enabled: Boolean = true,
    /** A score at or above this marks a person in a channel for the next review. */
    val threshold: Double = 10.0,
    /** How long it takes a score to fall by half with nothing new added. */
    val halfLife: Duration = Duration.ofMinutes(30),
    /** How far back repetition and floods look, and how much of the log a review reads. */
    val window: Duration = Duration.ofHours(1),
    /** How often the review runs. */
    val reviewInterval: Duration = Duration.ofHours(1),
    /** How many of a person's lines a review reads at most (their most recent). */
    val reviewLines: Int = 20,
    /** How many lines before and after each of theirs a review shows, for context. */
    val contextLines: Int = 3,
    /**
     * A flagged line that added at least this is a strong one, which the admin windows pre-check
     * for hiding (#169): an aimed insult (8), personal details (3), a slur or threat (10) are; a
     * repeat, a flood line or swearing at no one aren't.
     */
    val strongLineWeight: Double = 3.0,
    val weights: Weights = Weights(),
    /** More lines than this in [floodWindow] is a flood. */
    val floodLines: Int = 6,
    val floodWindow: Duration = Duration.ofSeconds(10),
    val profanity: List<String> = ModerationWords.PROFANITY,
    val insults: List<String> = ModerationWords.INSULTS,
    val slurs: List<String> = ModerationWords.SLURS,
    /** Hosts whose links count against the poster (a host matches its subdomains too). */
    val blockedHosts: List<String> = emptyList(),
) {
    /** What each signal adds to a score. */
    data class Weights(
        /** Swearing aimed at no one: at code, at oneself, at the build. */
        val profanity: Double = 0.5,
        /** An insult aimed at no one in particular. */
        val insult: Double = 1.0,
        /** Swearing or an insult aimed at someone: "you", or alongside another person's name. */
        val aimedHostility: Double = 8.0,
        /** A slur, on its own. */
        val slur: Double = 10.0,
        /** A threat of violence at someone, or telling them to kill themselves. */
        val threat: Double = 10.0,
        /** An email address, phone number or street address posted. */
        val personalDetails: Double = 3.0,
        /** The same, alongside another person's name: the shape of doxxing. */
        val personalDetailsAboutSomeone: Double = 8.0,
        /** A link to a blocked host. */
        val blockedLink: Double = 6.0,
        /** Each earlier copy of the same line in the window adds this much again. */
        val repetition: Double = 1.0,
        /** Each line past [floodLines] in [floodWindow]. */
        val flood: Double = 1.0,
    )
}
