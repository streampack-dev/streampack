/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.subscription

import dev.streampack.forge.ForgeKind

/** A stand-in in a selector for `prs` or `mrs` */
private const val CHANGE_REQUESTS = "{cr}"

/**
 * A kind of issue, change request or release event that a subscription can ask for (#267).
 *
 * [token] is the value stored on the subscription. The tokens of the first three kinds are the ones
 * every subscription stored before #267, so stored subscriptions keep their meaning. [selector] is
 * the word a subscribe line uses; a change request's selector is `prs` or `mrs`, as the forge says
 * it, and either word works on either forge.
 */
enum class EventKind(val token: String, private val selector: String) {
    ISSUES_OPENED("issues", "issues"),
    ISSUES_CLOSED("issues:closed", "issues:closed"),
    CHANGE_REQUESTS_OPENED("change_requests", CHANGE_REQUESTS),
    CHANGE_REQUESTS_MERGED("change_requests:merged", "$CHANGE_REQUESTS:merged"),
    CHANGE_REQUESTS_CLOSED("change_requests:closed", "$CHANGE_REQUESTS:closed"),
    RELEASES("releases", "releases");

    /** The selector as [forge] says it: `prs:merged` on GitHub, `mrs:merged` on GitLab */
    fun selector(forge: ForgeKind): String =
        selector.replace(CHANGE_REQUESTS, forge.changeRequestNoun.lowercase() + "s")

    companion object {
        /** The kinds a subscription gets when it names none: what every subscription got before */
        val DEFAULTS: List<EventKind> = listOf(ISSUES_OPENED, CHANGE_REQUESTS_OPENED, RELEASES)

        /** The kinds a word on a subscribe line selects, or null when it isn't a selector */
        fun parse(word: String): EventKind? {
            val lower = word.trim().lowercase()
            val normalized =
                lower.replace(Regex("^(prs|mrs)(?=$|:)"), CHANGE_REQUESTS).removeSuffix(":opened")
            return entries.firstOrNull { it.selector == normalized }
        }

        /** The kind stored as [token], or null when it isn't one */
        fun fromToken(token: String): EventKind? = entries.firstOrNull { it.token == token }
    }
}
