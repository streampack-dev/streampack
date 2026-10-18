/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import com.rometools.modules.itunes.AbstractITunesObject
import com.rometools.modules.itunes.EntryInformationImpl
import com.rometools.modules.itunes.FeedInformationImpl
import com.rometools.modules.itunes.types.Category
import com.rometools.modules.itunes.types.Duration
import com.rometools.modules.itunes.types.Subcategory
import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.io.SyndFeedOutput
import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.config.PodcastProperties
import dev.streampack.blog.config.RssFeedProperties
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostVersion
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.blog.service.MarkdownRenderingService
import dev.streampack.blog.service.PostVersionService
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import java.util.Date
import org.jdom2.Document
import org.jdom2.Element
import org.jdom2.Namespace
import org.jdom2.output.Format
import org.jdom2.output.XMLOutputter
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Generates an RSS or Atom feed of published blog posts using the public frontend host. A post with
 * a recording carries it as an enclosure (#196), on the API's own origin, which front ends must not
 * rewrite.
 *
 * `/podcast.xml` is the same feed limited to the posts with a recording, with the `itunes:` tags
 * podcast apps read; its channel details are [PodcastProperties].
 */
@RestController
class BlogFeedController(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val blogProperties: BlogProperties,
    private val markdownRenderingService: MarkdownRenderingService,
    private val postVersionService: PostVersionService,
    private val podcastProperties: PodcastProperties,
    private val feedProperties: RssFeedProperties,
) {
    @GetMapping(value = ["/feed.xml", "/feed.atom"], produces = [MediaType.APPLICATION_XML_VALUE])
    fun feed(request: HttpServletRequest): ResponseEntity<String> =
        render(request, atom = request.requestURI.endsWith(".atom"), podcast = false)

    @GetMapping(value = ["/podcast.xml"], produces = [MediaType.APPLICATION_XML_VALUE])
    fun podcast(request: HttpServletRequest): ResponseEntity<String> =
        render(request, atom = false, podcast = true)

    private fun render(
        request: HttpServletRequest,
        atom: Boolean,
        podcast: Boolean,
    ): ResponseEntity<String> {
        val now = Instant.now()
        val (posts, audio) =
            if (podcast) {
                val versions = postVersionService.publishedAudio(now)
                versions.map { it.post } to versions.associateBy { it.post.id }
            } else {
                val published = postRepository.findPublishedForFeeds(now)
                published to postVersionService.audioByPost(published.map { it.id })
            }
        val baseUrl = resolveBaseUrl(request)

        val feed = SyndFeedImpl()
        feed.feedType = if (atom) "atom_1.0" else "rss_2.0"
        // The same channel as /blog/rss.xml (#233); the podcast's is set below.
        feed.title = feedProperties.title.ifBlank { blogProperties.siteName }
        feed.link = baseUrl
        feed.description = feedProperties.description
        if (podcast) describePodcast(feed)

        feed.entries = posts.mapNotNull { post ->
            val slug = slugRepository.findCanonical(post.id) ?: return@mapNotNull null
            if (!BLOG_POST_SLUG.matches(slug.path)) return@mapNotNull null

            val entry = SyndEntryImpl()
            entry.title = post.title
            entry.link = "$baseUrl/posts/${slug.path}"
            entry.publishedDate = Date.from(post.publishedAt ?: post.createdAt)
            entry.author = post.author?.displayName ?: "Anonymous"

            val content = SyndContentImpl()
            content.type = "text/plain"
            content.value = excerptOf(post)
            entry.description = content

            audio[post.id]?.let { version ->
                entry.enclosures = mutableListOf(postVersionService.enclosure(version))
                // Added beside the Dublin Core module, which holds the entry's date and author.
                if (podcast) entry.modules.add(episode(post, version))
            }
            entry
        }

        val xml =
            if (podcast) {
                val document = SyndFeedOutput().outputJDom(feed)
                tidyPodcast(document)
                XMLOutputter(Format.getPrettyFormat()).outputString(document)
            } else {
                SyndFeedOutput().outputString(feed)
            }

        val contentType =
            if (atom) {
                MediaType.parseMediaType("application/atom+xml; charset=utf-8")
            } else {
                MediaType.parseMediaType("application/rss+xml; charset=utf-8")
            }
        return ResponseEntity.ok().contentType(contentType).body(xml)
    }

    private fun excerptOf(post: Post): String =
        post.excerpt?.takeIf { it.isNotBlank() }
            ?: markdownRenderingService.excerpt(post.markdownSource)

    /** The podcast channel: its title, description, language and `itunes:` tags. */
    private fun describePodcast(feed: SyndFeedImpl) {
        val settings = podcastProperties
        feed.title = settings.title.ifBlank { blogProperties.siteName }
        feed.description =
            settings.description.ifBlank { "Posts from ${blogProperties.siteName}, read aloud" }
        feed.language = settings.language.ifBlank { null }
        val show = FeedInformationImpl()
        show.summary = feed.description
        show.explicitNullable = settings.explicit
        settings.author.takeIf { it.isNotBlank() }?.let { show.author = it }
        settings.image.takeIf { it.isNotBlank() }?.let { show.imageUri = it }
        settings.category
            .takeIf { it.isNotBlank() }
            ?.let { configured ->
                val name = configured.substringBefore('/').trim()
                val category = Category(name)
                configured
                    .substringAfter('/', "")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { category.subcategory = Subcategory(it) }
                show.categories = mutableListOf(category)
            }
        settings.ownerName.takeIf { it.isNotBlank() }?.let { show.ownerName = it }
        settings.ownerEmail.takeIf { it.isNotBlank() }?.let { show.ownerEmailAddress = it }
        // Added beside the Dublin Core module, which holds the feed's language.
        feed.modules.add(show)
    }

    /**
     * What ROME's iTunes generator writes that podcast directories don't want: empty `itunes:`
     * elements (`keywords`, an `owner` with no name or email), and `itunes:explicit` as yes/no
     * where Apple now asks for true/false.
     */
    private fun tidyPodcast(document: Document) {
        val itunes = Namespace.getNamespace(AbstractITunesObject.URI)
        fun itunesElements() =
            document.descendants.filterIsInstance<Element>().filter { it.namespace == itunes }
        itunesElements()
            .filter { it.children.isEmpty() && it.attributes.isEmpty() && it.textTrim.isEmpty() }
            .toList()
            .forEach { it.detach() }
        itunesElements()
            .filter { it.name == "owner" && it.children.isEmpty() }
            .toList()
            .forEach { it.detach() }
        itunesElements()
            .filter { it.name == "explicit" }
            .forEach { it.text = if (it.textTrim == "yes") "true" else "false" }
    }

    /** An episode's `itunes:` tags: its duration when known, and its summary. */
    private fun episode(post: Post, version: PostVersion): EntryInformationImpl {
        val episode = EntryInformationImpl()
        episode.title = post.title
        episode.summary = excerptOf(post)
        version.durationSeconds?.let { episode.duration = Duration(it * 1000L) }
        version.note?.let { episode.subtitle = it }
        return episode
    }

    private fun resolveBaseUrl(request: HttpServletRequest): String {
        val forwardedProto = request.getHeader("X-Forwarded-Proto")?.substringBefore(',')?.trim()
        val forwardedHost = request.getHeader("X-Forwarded-Host")?.substringBefore(',')?.trim()
        val scheme =
            if (forwardedProto == "http" || forwardedProto == "https") forwardedProto
            else request.scheme

        if (forwardedHost.isNullOrBlank()) {
            return blogProperties.baseUrl.trimEnd('/')
        }

        return "$scheme://$forwardedHost".trimEnd('/')
    }

    companion object {
        private val BLOG_POST_SLUG = Regex("""\d{4}/\d{2}/.+""")
    }
}
