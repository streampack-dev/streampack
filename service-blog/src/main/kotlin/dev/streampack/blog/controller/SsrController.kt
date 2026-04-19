/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.BlogProperties
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.blog.model.RecordPostAccessRequest
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Server-side rendered HTML pages for search engine crawlers */
@RestController
@RequestMapping("/ssr")
class SsrController(
    private val eventGateway: EventGateway,
    private val blogProperties: BlogProperties,
) {
    private val siteName
        get() = blogProperties.siteName

    private val baseUrl
        get() = blogProperties.baseUrl.trimEnd('/')

    @GetMapping("/", produces = [MediaType.TEXT_HTML_VALUE])
    fun home(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): ResponseEntity<String> {
        val result = dispatch(FindContentRequest.FindPublished(page, size))

        if (result !is OperationResult.Success) {
            return htmlResponse(
                siteName,
                "$baseUrl/",
                pageHtml("$siteName", "<p>No posts yet.</p>"),
            )
        }

        val response = result.payload as ContentListResponse
        val postLinks =
            response.posts.joinToString("\n") { post ->
                val url = "$baseUrl/posts/${esc(post.slug)}"
                val date = post.publishedAt?.toString()?.take(10) ?: ""
                """<article><h2><a href="$url">${esc(post.title)}</a></h2>
               <p>${esc(post.excerpt ?: "")}</p>
               <small>By ${esc(post.authorDisplayName)} | $date</small></article>"""
            }

        return htmlResponse(
            siteName,
            "$baseUrl/",
            pageHtml(siteName, postLinks, description = "Latest posts on $siteName"),
        )
    }

    @GetMapping("/posts/{year}/{month}/{slug}", produces = [MediaType.TEXT_HTML_VALUE])
    fun post(
        @PathVariable year: Int,
        @PathVariable month: Int,
        @PathVariable slug: String,
    ): ResponseEntity<String> {
        val path = "$year/${"%02d".format(month)}/$slug"
        val result = dispatch(FindContentRequest.FindBySlug(path))

        if (result !is OperationResult.Success) {
            return notFoundHtml()
        }

        val detail = result.payload as ContentDetail
        eventGateway.send(MessageBuilder.withPayload(RecordPostAccessRequest(detail.id)).build())
        val canonicalUrl = "$baseUrl/posts/${esc(detail.slug)}"
        val tags = detail.tags.joinToString(", ")

        val html =
            """
            <article>
                <header>
                    <h1>${esc(detail.title)}</h1>
                    <p>By ${esc(detail.authorDisplayName)}
                    ${detail.publishedAt?.let { "| ${it.toString().take(10)}" } ?: ""}</p>
                </header>
                ${detail.renderedHtml}
                ${if (tags.isNotBlank()) "<footer><p>Tags: $tags</p></footer>" else ""}
            </article>
        """

        return htmlResponse(
            "${detail.title} - $siteName",
            canonicalUrl,
            pageHtml(detail.title, html, detail.excerpt, canonicalUrl),
        )
    }

    @GetMapping("/pages/{slug}", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(@PathVariable slug: String): ResponseEntity<String> {
        val result = dispatch(FindContentRequest.FindPage(slug))

        if (result !is OperationResult.Success) {
            return notFoundHtml()
        }

        val detail = result.payload as ContentDetail
        val canonicalUrl = "$baseUrl/pages/${esc(slug)}"

        val html =
            """
            <article>
                <h1>${esc(detail.title)}</h1>
                ${detail.renderedHtml}
            </article>
        """

        return htmlResponse(
            "${detail.title} - $siteName",
            canonicalUrl,
            pageHtml(detail.title, html, detail.excerpt, canonicalUrl),
        )
    }

    private fun dispatch(payload: FindContentRequest): OperationResult {
        val provenance =
            Provenance(
                protocol = Protocol.HTTP,
                serviceId = blogProperties.serviceId,
                replyTo = "ssr",
            )
        val message =
            MessageBuilder.withPayload(payload as Any)
                .setHeader(Provenance.HEADER, provenance)
                .build()

        return when (val result = eventGateway.process(message)) {
            is OperationResult.Success -> result
            else -> OperationResult.Error("Not found")
        }
    }

    /** Wraps content in a minimal HTML page with meta tags */
    private fun pageHtml(
        title: String,
        body: String,
        description: String? = null,
        canonicalUrl: String? = null,
    ): String {
        val desc = description ?: title
        val canonical = canonicalUrl ?: baseUrl
        return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>${esc(title)} - $siteName</title>
    <meta name="description" content="${esc(desc)}">
    <meta property="og:title" content="${esc(title)}">
    <meta property="og:description" content="${esc(desc)}">
    <meta property="og:type" content="article">
    <meta property="og:site_name" content="${esc(siteName)}">
    <meta property="og:url" content="$canonical">
    <link rel="canonical" href="$canonical">
    <link rel="alternate" type="application/rss+xml" title="${esc(siteName)} RSS" href="$baseUrl/feed.xml">
    <meta name="robots" content="index, follow">
</head>
<body>
    <header><h1><a href="$baseUrl">${esc(siteName)}</a></h1></header>
    <main>$body</main>
    <footer><p><a href="$baseUrl/about">About</a></p></footer>
</body>
</html>"""
    }

    private fun htmlResponse(title: String, url: String, html: String): ResponseEntity<String> =
        ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html)

    private fun notFoundHtml(): ResponseEntity<String> =
        ResponseEntity.status(404)
            .contentType(MediaType.TEXT_HTML)
            .body(pageHtml("Not Found", "<p>Page not found.</p>"))

    /** Escape HTML entities in text content */
    private fun esc(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
