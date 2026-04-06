/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import dev.streampack.rss.entity.RssFeed

/** Result of attempting to add a feed */
sealed interface AddFeedOutcome {
    data class Added(val feed: RssFeed, val entryCount: Int) : AddFeedOutcome

    data class AlreadyExists(val feed: RssFeed) : AddFeedOutcome

    data class DiscoveryFailed(val url: String) : AddFeedOutcome
}
