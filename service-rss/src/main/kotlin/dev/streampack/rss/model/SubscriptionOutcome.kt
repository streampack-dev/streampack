/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.model

import dev.streampack.rss.entity.RssFeed

/** Result of attempting to subscribe or unsubscribe a destination to a feed */
sealed interface SubscriptionOutcome {
    data class Subscribed(val feed: RssFeed) : SubscriptionOutcome

    data class Unsubscribed(val feed: RssFeed) : SubscriptionOutcome

    data class AlreadySubscribed(val feed: RssFeed) : SubscriptionOutcome

    data class NotSubscribed(val feed: RssFeed) : SubscriptionOutcome

    data class FeedNotFound(val url: String) : SubscriptionOutcome
}
