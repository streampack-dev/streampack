/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntry
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeed
import com.rometools.rome.feed.synd.SyndFeedImpl
import dev.streampack.blog.config.RssFeedProperties
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.config.StreampackProperties
import java.time.Instant
import java.util.Date
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service

/** Builds an RSS 2.0 feed from published blog posts */
@Service
class RssFeedService(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val streampackProperties: StreampackProperties,
    private val rssFeedProperties: RssFeedProperties,
) {

    /** Assembles a SyndFeed containing the most recent published posts */
    fun buildFeed(): SyndFeed {
        val posts =
            postRepository.findRecentPublishedWithAuthor(
                Instant.now(),
                PageRequest.of(0, rssFeedProperties.itemCount),
            )

        val baseUrl = streampackProperties.baseUrl

        val feed = SyndFeedImpl()
        feed.feedType = "rss_2.0"
        feed.title = rssFeedProperties.title
        feed.description = rssFeedProperties.description
        feed.link = baseUrl
        feed.language = rssFeedProperties.language

        feed.entries =
            posts.map { post ->
                val slug = slugRepository.findCanonical(post.id)
                val entry: SyndEntry = SyndEntryImpl()
                entry.title = post.title
                entry.link = "$baseUrl/${slug?.path ?: post.id}"
                entry.uri = post.id.toString()

                val description = SyndContentImpl()
                description.type = "text/plain"
                description.value = post.excerpt ?: ""
                entry.description = description

                if (post.publishedAt != null) {
                    entry.publishedDate = Date.from(post.publishedAt)
                }
                entry.author = post.author?.displayName ?: ""
                entry
            }

        return feed
    }
}
