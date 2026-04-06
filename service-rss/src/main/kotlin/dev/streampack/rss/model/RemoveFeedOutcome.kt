/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import dev.streampack.rss.entity.RssFeed

/** Result of attempting to deactivate a feed */
sealed interface RemoveFeedOutcome {
    data class Removed(val feed: RssFeed, val subscriptionsDeactivated: Int) : RemoveFeedOutcome

    data class FeedNotFound(val url: String) : RemoveFeedOutcome

    data class AlreadyInactive(val feed: RssFeed) : RemoveFeedOutcome
}
