/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.subscription

import dev.streampack.forge.model.ForgeEvent

/**
 * The event tokens stored on a subscription and the rule for which events it receives.
 *
 * Every subscription carries [BASE]; pipeline filters are appended as rendered [PipelineFilter]
 * tokens. Tokens the running version does not recognize are ignored, so a downgrade never breaks
 * fan-out.
 */
object SubscriptionEvents {
    const val ISSUES = "issues"
    const val CHANGE_REQUESTS = "change_requests"
    const val RELEASES = "releases"

    val BASE: List<String> = listOf(ISSUES, CHANGE_REQUESTS, RELEASES)

    /** The stored token list for a subscription with [filters]. */
    fun withFilters(filters: List<PipelineFilter>): List<String> =
        BASE + filters.map { it.render() }.distinct()

    fun pipelineFilters(events: List<String>): List<PipelineFilter> =
        events.mapNotNull { PipelineFilter.parse(it) }

    fun wants(events: List<String>, event: ForgeEvent): Boolean =
        when (event) {
            is ForgeEvent.IssueOpened -> ISSUES in events
            is ForgeEvent.ChangeRequestOpened -> CHANGE_REQUESTS in events
            is ForgeEvent.ReleasePublished -> RELEASES in events
            is ForgeEvent.PipelineSettled -> pipelineFilters(events).any { it.matches(event) }
            is ForgeEvent.Ping -> true
        }
}
