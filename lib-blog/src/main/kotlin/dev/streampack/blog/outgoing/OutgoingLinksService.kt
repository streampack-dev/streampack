/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.outgoing

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.entity.Post
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.json.JacksonMappers
import dev.streampack.core.model.LinkedSiteSubscriptionRequest
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.messaging.support.MessageBuilder
import org.springframework.stereotype.Service

/**
 * Handles the outgoing links of published posts: tells each linked page of the mention (#112) and
 * asks the RSS reader to follow each linked site's feed (#128), once each.
 *
 * A post is due when it's live and `metadata.linksCheckedThrough` is short of its `updatedAt`: just
 * published (a scheduled one once its time comes), or edited since. Each pass records, in the
 * post's metadata, the links mentioned (`mentioned`) and the hosts looked at for a feed
 * (`feedsChecked`), whatever came of them, so none is tried twice; and `linksCheckedThrough`, the
 * update it handled. A post marked `linksBaseline` (one already live when this began) has its links
 * recorded as handled without anything being sent, the first time it's due.
 *
 * Visibility is the log: one line per link mentioned and per site looked at.
 */
@Service
class OutgoingLinksService(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val mentionSender: MentionSender,
    private val eventGateway: EventGateway,
    private val blogProperties: BlogProperties,
    private val properties: OutgoingLinksProperties,
) {
    private val logger = LoggerFactory.getLogger(OutgoingLinksService::class.java)
    private val mapper = JacksonMappers.standard()

    /** Handles the posts due at [now], up to a batch; returns how many were handled. */
    fun runBatch(now: Instant): Int {
        val due = postRepository.findOutgoingLinksDue(now, properties.batchSize)
        due.forEach { post ->
            try {
                handle(post)
            } catch (e: Exception) {
                logger.warn("Outgoing links of post {} failed: {}", post.id, e.message)
            }
        }
        return due.size
    }

    /** Handles one due [post]: its links mentioned, its sites looked at, both recorded. */
    internal fun handle(post: Post) {
        val checkedThrough = post.updatedAt.toEpochMilli()
        val slug = slugRepository.findCanonical(post.id)
        val ownHost = runCatching { URI(blogProperties.baseUrl).host }.getOrNull()
        val links = OutgoingLinks.extract(post.renderedHtml, ownHost)
        val mentioned = stringList(post.metadata["mentioned"])
        val feedsChecked = stringList(post.metadata["feedsChecked"])
        val hosts = links.groupBy { OutgoingLinks.host(it)!! }

        if (post.metadata["linksBaseline"] == true || slug == null) {
            // Already live before this began, or with no public address: recorded, not sent.
            record(
                post,
                checkedThrough,
                (mentioned + links).distinct(),
                (feedsChecked + hosts.keys).distinct(),
            )
            return
        }
        val source = "${blogProperties.baseUrl.trimEnd('/')}/posts/${slug.path}"

        val newlyMentioned = mutableListOf<String>()
        if (properties.mentions && publicSource(source)) {
            for (target in links.filter { it !in mentioned }) {
                val result = mentionSender.mention(source, target)
                logger.info("Mention {} from {}: {}", target, source, result.text)
                newlyMentioned += target
            }
        }

        val newlyChecked = mutableListOf<String>()
        if (properties.autosubscribe) {
            for ((host, pages) in hosts.filterKeys { it !in feedsChecked }) {
                logger.info(
                    "Autosubscribe {} from {}: {}",
                    host,
                    source,
                    subscribe(pages.first(), source),
                )
                newlyChecked += host
            }
        }

        record(post, checkedThrough, mentioned + newlyMentioned, feedsChecked + newlyChecked)
    }

    private fun subscribe(pageUrl: String, source: String): String {
        val message =
            MessageBuilder.withPayload(LinkedSiteSubscriptionRequest(pageUrl, source) as Any)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog",
                        replyTo = "outgoing-links",
                    ),
                )
                .build()
        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> result.payload.toString()
            is OperationResult.Error -> "failed: ${result.message}"
            OperationResult.NotHandled -> "no RSS reader"
        }
    }

    private fun record(
        post: Post,
        checkedThrough: Long,
        mentioned: List<String>,
        feedsChecked: List<String>,
    ) {
        val patch =
            mapOf(
                "linksCheckedThrough" to checkedThrough,
                "linksBaseline" to false,
                "mentioned" to mentioned,
                "feedsChecked" to feedsChecked,
            )
        postRepository.mergeMetadata(post.id, mapper.writeValueAsString(patch))
    }

    /**
     * Whether the receiver could fetch [source] to check it: not while the blog's address is this
     * machine or a private network's, as in development, where a mention would only be refused.
     */
    private fun publicSource(source: String): Boolean {
        val host = runCatching { URI(source).host }.getOrNull() ?: return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            return false
        }
        if (!IP_LITERAL.matches(host)) return true
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return !(address.isLoopbackAddress ||
            address.isSiteLocalAddress ||
            address.isLinkLocalAddress)
    }

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private companion object {
        val IP_LITERAL = Regex("""^[\d.]+$|^\[?[0-9a-fA-F:]+]?$""")
    }
}
