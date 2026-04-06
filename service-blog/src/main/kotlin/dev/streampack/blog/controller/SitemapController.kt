/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Generates a sitemap.xml for search engine crawlers */
@RestController
class SitemapController(
    private val postRepository: PostRepository,
    private val slugRepository: SlugRepository,
    private val blogProperties: BlogProperties,
) {
    private val baseUrl
        get() = blogProperties.baseUrl.trimEnd('/')

    @GetMapping("/sitemap.xml", produces = [MediaType.APPLICATION_XML_VALUE])
    fun sitemap(): ResponseEntity<String> {
        val now = Instant.now()
        val posts = postRepository.findPublished(now)

        val urls = StringBuilder()

        // Home page
        urls.append(urlEntry(baseUrl, now, "daily", "1.0"))

        // Published posts
        for (post in posts) {
            val slug = slugRepository.findCanonical(post.id)
            if (slug != null) {
                val url = "$baseUrl/posts/${slug.path}"
                val lastMod = post.updatedAt ?: post.publishedAt ?: post.createdAt
                urls.append(urlEntry(url, lastMod, "weekly", "0.8"))
            }
        }

        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
$urls</urlset>"""

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_XML).body(xml)
    }

    private fun urlEntry(
        loc: String,
        lastMod: Instant,
        changeFreq: String,
        priority: String,
    ): String {
        val date = DATE_FORMAT.format(lastMod)
        return """  <url>
    <loc>$loc</loc>
    <lastmod>$date</lastmod>
    <changefreq>$changeFreq</changefreq>
    <priority>$priority</priority>
  </url>
"""
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)
    }
}
