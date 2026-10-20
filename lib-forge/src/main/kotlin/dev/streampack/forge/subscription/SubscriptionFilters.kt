/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.subscription

import dev.streampack.forge.ForgeKind

/**
 * What a subscribe line asked for (#267): the event [kinds], and the [pipelines] filters.
 *
 * [kinds] is null when the line named no event kinds. Then the subscription gets the defaults
 * ([EventKind.DEFAULTS]), as every subscription did before #267, with or without pipeline filters.
 * When the line names any kind, the kinds named are the whole list.
 */
data class SubscriptionFilters(
    val kinds: Set<EventKind>? = null,
    val pipelines: List<PipelineFilter> = emptyList(),
) {
    /** True when the line named nothing at all */
    val isEmpty: Boolean
        get() = kinds == null && pipelines.isEmpty()

    /** The tokens to store on the subscription */
    fun tokens(): List<String> =
        (kinds ?: EventKind.DEFAULTS).sortedBy { it.ordinal }.map { it.token } +
            pipelines.map { it.render() }.distinct()

    /**
     * ` [mrs:merged pipelines:failed]` in [forge]'s words, for replies and `subscriptions`; the
     * kinds are left out when they are the defaults, and the whole is empty when nothing is left.
     */
    fun render(forge: ForgeKind): String {
        val shown =
            kinds
                ?.takeIf { it != EventKind.DEFAULTS.toSet() }
                ?.sortedBy { it.ordinal }
                ?.map { it.selector(forge) }
                .orEmpty() + pipelines.map { it.render() }
        return if (shown.isEmpty()) "" else " [${shown.joinToString(" ")}]"
    }

    /** The result of reading a subscribe line's selectors */
    sealed interface Parsed {
        data class Filters(val filters: SubscriptionFilters) : Parsed

        /** [token] is neither an event selector nor a pipeline filter */
        data class Unknown(val token: String) : Parsed
    }

    companion object {
        val NONE = SubscriptionFilters()

        /** Reads the selector words after the project on a subscribe line */
        fun parse(words: List<String>): Parsed {
            val kinds = linkedSetOf<EventKind>()
            val pipelines = mutableListOf<PipelineFilter>()
            for (word in words) {
                val pipeline = PipelineFilter.parse(word)
                if (pipeline != null) {
                    pipelines += pipeline
                    continue
                }
                kinds += EventKind.parse(word) ?: return Parsed.Unknown(word)
            }
            return Parsed.Filters(SubscriptionFilters(kinds.ifEmpty { null }, pipelines))
        }

        /** What a subscription's stored [events] say it asked for */
        fun fromStored(events: List<String>): SubscriptionFilters =
            SubscriptionFilters(
                events.mapNotNull { EventKind.fromToken(it) }.toSet(),
                SubscriptionEvents.pipelineFilters(events),
            )

        /** The words a reply lists as selectors, in [forge]'s own terms */
        fun selectorHelp(forge: ForgeKind): String {
            val cr = forge.changeRequestNoun.lowercase() + "s"
            return "issues, issues:closed, $cr, $cr:merged, $cr:closed, releases, " +
                "and pipelines, pipelines:default-branch, pipelines:branch:<name>, " +
                "each pipelines filter optionally followed by :failed"
        }
    }
}
