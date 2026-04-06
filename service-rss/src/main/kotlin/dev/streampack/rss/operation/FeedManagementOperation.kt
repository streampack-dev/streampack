/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.polling.operation.PollingSourceManagementOperation
import dev.streampack.rss.model.RemoveFeedOutcome
import dev.streampack.rss.model.SubscriptionOutcome
import dev.streampack.rss.service.RssSubscriptionService
import org.springframework.stereotype.Component

/** Handles feed management commands: list, subscribe, unsubscribe, subscriptions, remove */
@Component
class FeedManagementOperation(private val feedService: RssSubscriptionService) :
    PollingSourceManagementOperation() {

    override val commandPrefix: String = "feed"
    override val priority: Int = 56
    override val operationGroup: String = "rss"

    override fun onList(): OperationOutcome {
        val feeds = feedService.listFeeds()
        if (feeds.isEmpty()) {
            return OperationResult.Success("No feeds registered")
        }
        val lines =
            feeds.joinToString("\n") { feed ->
                val status = if (feed.active) "" else " [inactive]"
                "${feed.title} - ${feed.feedUrl}$status"
            }
        return OperationResult.Success(lines)
    }

    override fun onSubscribe(identifier: String, destinationUri: String): OperationOutcome {
        return when (val outcome = feedService.subscribe(identifier, destinationUri)) {
            is SubscriptionOutcome.Subscribed ->
                OperationResult.Success("Subscribed to \"${outcome.feed.title}\"")
            is SubscriptionOutcome.AlreadySubscribed ->
                OperationResult.Success("Already subscribed to \"${outcome.feed.title}\"")
            is SubscriptionOutcome.FeedNotFound ->
                OperationResult.Error("No registered feed found for ${outcome.url}")
            is SubscriptionOutcome.Unsubscribed,
            is SubscriptionOutcome.NotSubscribed -> OperationResult.Error("Unexpected outcome")
        }
    }

    override fun onUnsubscribe(identifier: String, destinationUri: String): OperationOutcome {
        return when (val outcome = feedService.unsubscribe(identifier, destinationUri)) {
            is SubscriptionOutcome.Unsubscribed ->
                OperationResult.Success("Unsubscribed from \"${outcome.feed.title}\"")
            is SubscriptionOutcome.NotSubscribed ->
                OperationResult.Error("Not subscribed to \"${outcome.feed.title}\"")
            is SubscriptionOutcome.FeedNotFound ->
                OperationResult.Error("No registered feed found for ${outcome.url}")
            is SubscriptionOutcome.Subscribed,
            is SubscriptionOutcome.AlreadySubscribed -> OperationResult.Error("Unexpected outcome")
        }
    }

    override fun onSubscriptions(destinationUri: String): OperationOutcome {
        val subscriptions = feedService.listSubscriptions(destinationUri)
        if (subscriptions.isEmpty()) {
            return OperationResult.Success("No active subscriptions for this channel")
        }
        val lines = subscriptions.joinToString("\n") { it.feed.title + " - " + it.feed.feedUrl }
        return OperationResult.Success(lines)
    }

    override fun onRemove(identifier: String): OperationOutcome {
        return when (val outcome = feedService.removeFeed(identifier)) {
            is RemoveFeedOutcome.Removed ->
                OperationResult.Success(
                    "Removed feed \"${outcome.feed.title}\" " +
                        "(${outcome.subscriptionsDeactivated} subscriptions deactivated)"
                )
            is RemoveFeedOutcome.FeedNotFound ->
                OperationResult.Error("No registered feed found for ${outcome.url}")
            is RemoveFeedOutcome.AlreadyInactive ->
                OperationResult.Success("Feed \"${outcome.feed.title}\" is already inactive")
        }
    }
}
