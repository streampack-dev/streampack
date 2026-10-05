/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import dev.streampack.core.model.LinkedSiteSubscription
import dev.streampack.core.model.LinkedSiteSubscription.Outcome
import dev.streampack.core.model.LinkedSiteSubscriptionRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import dev.streampack.rss.config.RssProperties
import dev.streampack.rss.model.AddFeedOutcome
import dev.streampack.rss.service.FeedDiscoveryService
import dev.streampack.rss.service.RssSubscriptionService
import java.net.URI
import org.springframework.messaging.Message
import org.springframework.stereotype.Component

/**
 * Follows the site a published post links to (#128): unless its host is one never subscribed to, or
 * we already have a feed on it, discovers its site feed (never a comments feed) and adds it. The
 * request comes from the blog's outgoing-links pass, never from a person, so it isn't addressed and
 * needs no role.
 */
@Component
class LinkedSiteSubscriptionOperation(
    private val subscriptions: RssSubscriptionService,
    private val discovery: FeedDiscoveryService,
    private val properties: RssProperties,
) : TypedOperation<LinkedSiteSubscriptionRequest>(LinkedSiteSubscriptionRequest::class) {

    override val addressed: Boolean = false
    override val operationGroup: String = "rss"

    override fun handle(
        payload: LinkedSiteSubscriptionRequest,
        message: Message<*>,
    ): OperationOutcome = OperationResult.Success(subscribe(payload.pageUrl))

    private fun subscribe(pageUrl: String): LinkedSiteSubscription {
        val host =
            runCatching { URI(pageUrl).host }.getOrNull()?.lowercase()?.removePrefix("www.")
                ?: return LinkedSiteSubscription(Outcome.SKIPPED, detail = "no host")
        properties.autosubscribe.skipHosts
            .map { it.lowercase().removePrefix("www.") }
            .firstOrNull { host == it || host.endsWith(".$it") }
            ?.let {
                return LinkedSiteSubscription(Outcome.SKIPPED, detail = "$it isn't subscribed to")
            }
        subscriptions.knownFeedOnHost(host)?.let {
            return LinkedSiteSubscription(Outcome.ALREADY_HAVE, feedUrl = it.feedUrl)
        }
        val found =
            discovery.discoverSiteFeed(pageUrl) ?: return LinkedSiteSubscription(Outcome.NO_FEED)
        return when (val outcome = subscriptions.register(found)) {
            is AddFeedOutcome.Added ->
                LinkedSiteSubscription(Outcome.ADDED, feedUrl = outcome.feed.feedUrl)
            is AddFeedOutcome.AlreadyExists ->
                LinkedSiteSubscription(Outcome.ALREADY_HAVE, feedUrl = outcome.feed.feedUrl)
            is AddFeedOutcome.DiscoveryFailed -> LinkedSiteSubscription(Outcome.NO_FEED)
        }
    }
}
