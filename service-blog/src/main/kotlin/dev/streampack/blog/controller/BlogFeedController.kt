/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import com.rometools.rome.feed.synd.SyndContentImpl
import com.rometools.rome.feed.synd.SyndEntryImpl
import com.rometools.rome.feed.synd.SyndFeedImpl
import com.rometools.rome.io.SyndFeedOutput
import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.blog.service.MarkdownRenderingService
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import java.util.Date
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Generates an RSS or Atom feed of published blog posts using the public frontend host. */
@RestController
class BlogFeedController(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val blogProperties: BlogProperties,
    private val markdownRenderingService: MarkdownRenderingService,
) {
    @GetMapping(value = ["/feed.xml", "/feed.atom"], produces = [MediaType.APPLICATION_XML_VALUE])
    fun feed(request: HttpServletRequest): ResponseEntity<String> {
        val now = Instant.now()
        val posts = postRepository.findPublished(now)
        val baseUrl = resolveBaseUrl(request)
        val atomRequested = request.requestURI.endsWith(".atom")

        val feed = SyndFeedImpl()
        feed.feedType = if (atomRequested) "atom_1.0" else "rss_2.0"
        feed.title = blogProperties.siteName
        feed.link = baseUrl
        feed.description = "Latest posts on ${blogProperties.siteName}"

        feed.entries =
            posts.mapNotNull { post ->
                val slug = slugRepository.findCanonical(post.id) ?: return@mapNotNull null
                if (!BLOG_POST_SLUG.matches(slug.path)) return@mapNotNull null

                val entry = SyndEntryImpl()
                entry.title = post.title
                entry.link = "$baseUrl/posts/${slug.path}"
                entry.publishedDate = Date.from(post.publishedAt ?: post.createdAt)
                entry.author = post.author?.displayName ?: "Anonymous"

                val content = SyndContentImpl()
                content.type = "text/plain"
                content.value =
                    post.excerpt?.takeIf { it.isNotBlank() }
                        ?: markdownRenderingService.excerpt(post.markdownSource)
                entry.description = content

                entry
            }

        val output = SyndFeedOutput()
        val xml = output.outputString(feed)

        val contentType =
            if (atomRequested) {
                MediaType.parseMediaType("application/atom+xml; charset=utf-8")
            } else {
                MediaType.parseMediaType("application/rss+xml; charset=utf-8")
            }
        return ResponseEntity.ok().contentType(contentType).body(xml)
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
