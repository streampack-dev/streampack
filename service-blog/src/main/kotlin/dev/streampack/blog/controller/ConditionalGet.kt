/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.config.ApiVersionHeaderFilter
import dev.streampack.blog.repository.ContentValidators.Validator
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import org.springframework.http.CacheControl
import org.springframework.http.ETag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component

/**
 * Conditional GETs for public responses (#83). The validator is computed first, from a cheap query;
 * a matching `If-None-Match` (or, without one, `If-Modified-Since`) is answered 304 before the
 * operation that builds the body ever runs. Otherwise the body is produced and carries the weak
 * ETag, `Last-Modified` and `Cache-Control: no-cache`, so browsers and proxies keep it but ask each
 * time.
 *
 * A response that differs per reader ([personal], e.g. drafts or markdown shown to their author)
 * gets no validator and `private, no-store`. The resolved API version is part of every ETag, and
 * `Vary` says so.
 */
@Component
class ConditionalGet {
    fun respond(
        request: HttpServletRequest,
        response: HttpServletResponse,
        personal: Boolean,
        key: List<Any?>,
        validator: () -> Validator?,
        produce: () -> ResponseEntity<*>,
    ): ResponseEntity<*> {
        if (personal) return withHeaders(produce(), privateNoStore())
        val v = validator() ?: return produce()
        val version = response.getHeader(ApiVersionHeaderFilter.ACCEPT_VERSION_HEADER).orEmpty()
        val etag = etagOf(listOf(version) + key + v.fingerprint)
        val lastModified = v.lastModified?.let { Instant.ofEpochSecond(it.epochSecond) }

        if (notModified(request, etag, lastModified)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .eTag(etag)
                .apply { lastModified?.let { lastModified(it) } }
                .cacheControl(CacheControl.noCache())
                .varyBy(*VARY)
                .build<Any>()
        }
        val body = produce()
        if (!body.statusCode.is2xxSuccessful) return body
        val headers = HttpHeaders()
        headers.addAll(body.headers)
        headers.eTag = etag
        lastModified?.let { headers.lastModified = it.toEpochMilli() }
        headers.cacheControl = CacheControl.noCache().headerValue
        headers.setVary(VARY.toList())
        return ResponseEntity(body.body, headers, body.statusCode)
    }

    /**
     * For small, per-deployment answers with nothing to derive from: an ETag of the body itself.
     */
    fun <T : Any> respondWithBody(
        request: HttpServletRequest,
        response: HttpServletResponse,
        body: T,
    ): ResponseEntity<*> =
        respond(
            request,
            response,
            personal = false,
            key = emptyList(),
            validator = { Validator(body.toString(), null) },
            produce = { ResponseEntity.ok(body) },
        )

    private fun notModified(
        request: HttpServletRequest,
        etag: String,
        lastModified: Instant?,
    ): Boolean {
        val ifNoneMatch = request.getHeaders(HttpHeaders.IF_NONE_MATCH).toList()
        if (ifNoneMatch.isNotEmpty()) {
            val ours = ETag.create(etag)
            return ifNoneMatch
                .flatMap { ETag.parse(it) }
                .any { it.isWildcard || it.compare(ours, false) }
        }
        val since = runCatching {
            request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE)
        }
            .getOrDefault(-1L)
        return lastModified != null && since >= 0 && lastModified.toEpochMilli() <= since
    }

    private fun withHeaders(entity: ResponseEntity<*>, cache: CacheControl): ResponseEntity<*> {
        val headers = HttpHeaders()
        headers.addAll(entity.headers)
        headers.cacheControl = cache.headerValue
        return ResponseEntity(entity.body, headers, entity.statusCode)
    }

    private fun privateNoStore() = CacheControl.noStore().cachePrivate()

    companion object {
        private val VARY =
            arrayOf(ApiVersionHeaderFilter.ACCEPT_VERSION_HEADER, HttpHeaders.AUTHORIZATION)

        fun etagOf(parts: List<Any?>): String {
            val digest =
                MessageDigest.getInstance("SHA-256")
                    .digest(parts.joinToString("\u0000").toByteArray())
            return "W/\"" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest).take(27) +
                "\""
        }
    }
}
