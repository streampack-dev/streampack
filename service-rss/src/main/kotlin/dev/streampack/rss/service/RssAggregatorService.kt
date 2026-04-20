/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.model.RssAggregatedItemResponse
import dev.streampack.rss.model.RssAggregatedItemsResponse
import dev.streampack.rss.model.RssFeedSourceResponse
import dev.streampack.rss.model.RssFeedSourcesResponse
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import jakarta.persistence.criteria.JoinType
import java.time.Instant
import java.util.UUID
import org.springframework.data.domain.PageRequest
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Read-only aggregator over stored RSS entries. */
@Service
class RssAggregatorService(
    private val entryRepository: RssEntryRepository,
    private val feedRepository: RssFeedRepository,
) {

    fun listItems(page: Int, size: Int, feed: String?, title: String?): RssAggregatedItemsResponse {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, 200))
        val normalizedFeed = feed?.trim()?.ifBlank { null }
        val normalizedTitle = title?.trim()?.ifBlank { null }
        val results =
            entryRepository.findAll(
                aggregatedEntrySpecification(normalizedFeed, normalizedTitle),
                pageable,
            )
        return RssAggregatedItemsResponse(
            items = results.content.map { it.toResponse() },
            page = results.number,
            totalPages = results.totalPages,
            totalCount = results.totalElements,
        )
    }

    fun listFeeds(): RssFeedSourcesResponse =
        RssFeedSourcesResponse(
            feeds =
                feedRepository
                    .findAllByActiveTrue()
                    .sortedBy { it.title.lowercase() }
                    .map { feed ->
                        RssFeedSourceResponse(
                            title = feed.title,
                            feedUrl = feed.feedUrl,
                            siteUrl = feed.siteUrl,
                            itemCount = entryRepository.countByFeed(feed),
                            latestItemTimestamp = entryRepository.findLatestItemTimestamp(feed),
                        )
                    }
        )

    @Transactional
    fun recordAccess(id: UUID) {
        val entry = entryRepository.findById(id).orElse(null) ?: return
        entryRepository.save(
            entry.copy(accessCount = entry.accessCount + 1, lastAccessedAt = Instant.now())
        )
    }

    private fun RssEntry.toResponse(): RssAggregatedItemResponse =
        RssAggregatedItemResponse(
            id = id,
            feedTitle = feed.title,
            feedUrl = feed.feedUrl,
            siteUrl = feed.siteUrl,
            guid = guid,
            link = link,
            title = title,
            publishedAt = publishedAt,
            receivedAt = createdAt,
        )

    private fun aggregatedEntrySpecification(
        feed: String?,
        title: String?,
    ): Specification<RssEntry> = Specification { root, query, criteriaBuilder ->
        val feedJoin =
            root.join<RssEntry, dev.streampack.rss.entity.RssFeed>("feed", JoinType.INNER)
        if (query.resultType != java.lang.Long::class.java) {
            root.fetch<dev.streampack.rss.entity.RssEntry, dev.streampack.rss.entity.RssFeed>(
                "feed",
                JoinType.INNER,
            )
            val effectiveTimestamp =
                criteriaBuilder
                    .coalesce<Instant>()
                    .value(root.get("publishedAt"))
                    .value(root.get("createdAt"))
            query.orderBy(
                criteriaBuilder.desc(effectiveTimestamp),
                criteriaBuilder.desc(root.get<Instant>("createdAt")),
            )
        }

        val predicates = mutableListOf(criteriaBuilder.isTrue(feedJoin.get("active")))

        if (feed != null) {
            val loweredFeed = feed.lowercase()
            predicates +=
                criteriaBuilder.or(
                    criteriaBuilder.equal(
                        criteriaBuilder.lower(feedJoin.get("title")),
                        loweredFeed,
                    ),
                    criteriaBuilder.equal(
                        criteriaBuilder.lower(feedJoin.get("feedUrl")),
                        loweredFeed,
                    ),
                )
        }

        if (title != null) {
            predicates +=
                criteriaBuilder.like(
                    criteriaBuilder.lower(root.get("title")),
                    "%${title.lowercase()}%",
                )
        }

        criteriaBuilder.and(*predicates.toTypedArray())
    }
}
