/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.model

/**
 * Asks the RSS reader, if there is one, to subscribe to the site feed of the site [pageUrl] is on
 * (#128), because a published post at [sourceUrl] links to it. Sent by the blog's outgoing-links
 * pass; answered with an [OperationResult.Success] whose payload is a [LinkedSiteSubscription].
 * With no RSS reader it isn't handled.
 */
data class LinkedSiteSubscriptionRequest(val pageUrl: String, val sourceUrl: String)

/** What came of a [LinkedSiteSubscriptionRequest], for the log. */
data class LinkedSiteSubscription(
    val outcome: Outcome,
    val feedUrl: String? = null,
    val detail: String? = null,
) {
    enum class Outcome {
        ADDED,
        ALREADY_HAVE,
        SKIPPED,
        NO_FEED,
        FAILED,
    }

    override fun toString(): String =
        when (outcome) {
            Outcome.ADDED -> "added $feedUrl"
            Outcome.ALREADY_HAVE -> "already have ${feedUrl ?: "a feed"}"
            Outcome.SKIPPED -> "skipped${detail?.let { " ($it)" } ?: ""}"
            Outcome.NO_FEED -> "no feed"
            Outcome.FAILED -> "failed: $detail"
        }
}
