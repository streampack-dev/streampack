/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.entity.RssFeedSubscription
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssFeedSubscriptionRepository
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

/**
 * `feed subscriptions` as it runs in production: no test transaction, so the feed must be loaded
 * with the subscription rather than lazily after the session closes.
 */
@SpringBootTest
class FeedSubscriptionsNoSessionTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var feedRepository: RssFeedRepository
    @Autowired lateinit var subscriptionRepository: RssFeedSubscriptionRepository

    private val channel = "#nosession-${UUID.randomUUID().toString().take(8)}"
    private val destination = "irc://libera/%23${channel.drop(1)}"
    private var feed: RssFeed? = null
    private var subscription: RssFeedSubscription? = null

    @AfterEach
    fun tearDown() {
        subscription?.let { subscriptionRepository.deleteById(it.id) }
        feed?.let { feedRepository.deleteById(it.id) }
    }

    @Test
    fun `feed subscriptions lists feed titles outside a transaction`() {
        val saved =
            feedRepository.save(
                RssFeed(
                    feedUrl = "https://example.com/$channel/feed.xml",
                    title = "No Session Feed",
                )
            )
        feed = saved
        subscription =
            subscriptionRepository.save(
                RssFeedSubscription(feed = saved, destinationUri = destination)
            )

        val provenance =
            Provenance(
                protocol = Protocol.IRC,
                serviceId = "libera",
                replyTo = channel,
                user =
                    UserPrincipal(
                        id = UUID.randomUUID(),
                        username = "reader",
                        displayName = "Reader",
                        role = Role.USER,
                    ),
            )
        val result =
            eventGateway.process(
                MessageBuilder.withPayload("feed subscriptions")
                    .setHeader(Provenance.HEADER, provenance)
                    .build()
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val payload = (result as OperationResult.Success).payload.toString()
        assertTrue(payload.contains("No Session Feed"), payload)
        assertTrue(payload.contains(saved.feedUrl), payload)
    }
}
