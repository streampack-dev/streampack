/* Joseph B. Ottinger (C)2026 */
package dev.streampack.forge.subscription

import dev.streampack.forge.model.ForgeEvent

/**
 * The event tokens stored on a subscription and the rule for which events it receives.
 *
 * A subscription stores one token per [EventKind] it receives, and its pipeline filters as rendered
 * [PipelineFilter] tokens. A subscription made before #267 stores [BASE], which still means what it
 * meant: issues, change requests and releases opened. Tokens the running version does not recognize
 * are ignored, so a downgrade never breaks fan-out.
 */
object SubscriptionEvents {
    const val ISSUES = "issues"
    const val CHANGE_REQUESTS = "change_requests"
    const val RELEASES = "releases"

    /** What a subscription gets when it names no event kinds: what every subscription got before */
    val BASE: List<String> = EventKind.DEFAULTS.map { it.token }

    fun pipelineFilters(events: List<String>): List<PipelineFilter> = events.mapNotNull {
        PipelineFilter.parse(it)
    }

    fun wants(events: List<String>, event: ForgeEvent): Boolean =
        when (event) {
            is ForgeEvent.IssueOpened -> EventKind.ISSUES_OPENED.token in events
            is ForgeEvent.IssueClosed -> EventKind.ISSUES_CLOSED.token in events
            is ForgeEvent.ChangeRequestOpened -> EventKind.CHANGE_REQUESTS_OPENED.token in events
            is ForgeEvent.ChangeRequestMerged -> EventKind.CHANGE_REQUESTS_MERGED.token in events
            is ForgeEvent.ChangeRequestClosed -> EventKind.CHANGE_REQUESTS_CLOSED.token in events
            is ForgeEvent.ReleasePublished -> EventKind.RELEASES.token in events
            is ForgeEvent.PipelineSettled -> pipelineFilters(events).any { it.matches(event) }
            is ForgeEvent.Ping -> true
        }

    /** True when [events] ask for closed issues, so polling must look for them */
    fun wantsIssueClosures(events: List<String>): Boolean = EventKind.ISSUES_CLOSED.token in events

    /** True when [events] ask for merged or closed change requests */
    fun wantsChangeRequestClosures(events: List<String>): Boolean =
        EventKind.CHANGE_REQUESTS_MERGED.token in events ||
            EventKind.CHANGE_REQUESTS_CLOSED.token in events

    /** True when [events] ask for any closed issue or change request */
    fun wantsClosures(events: List<String>): Boolean =
        wantsIssueClosures(events) || wantsChangeRequestClosures(events)
}
