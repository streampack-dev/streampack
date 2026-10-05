/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.Charset
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/**
 * Fetches addresses chosen by someone other than an admin (a post's links, a page to find a feed
 * on) without reaching anything that isn't public: every address, and every redirect's, is checked
 * by [AddressGuard] before it's connected to. Redirects are followed one hop at a time, up to
 * [FetchProperties.maxRedirects]; at most [FetchProperties.maxBytes] of a body is read.
 *
 * Fails with [FetchRefused] for an address it won't fetch and [FetchFailed] for one it couldn't;
 * any HTTP status, error or not, is a [FetchResponse].
 */
@Component
class GuardedFetcher
internal constructor(private val properties: FetchProperties, private val guard: AddressGuard) {
    @Autowired constructor(properties: FetchProperties) : this(properties, AddressGuard(properties))

    private val client: HttpClient =
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(properties.connectTimeout)
            .build()

    /** GETs [url], following redirects, reading at most [maxBytes] of the body. */
    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int = properties.maxBytes,
    ): FetchResponse {
        var uri = parse(url)
        repeat(properties.maxRedirects + 1) {
            val response = send(uri, headers, maxBytes) { it.GET() }
            val location = response.header("Location")
            if (response.status !in REDIRECTS || location == null) return response
            uri =
                try {
                    uri.resolve(location.trim())
                } catch (e: IllegalArgumentException) {
                    throw FetchFailed(uri, "bad redirect to $location", e)
                }
        }
        throw FetchFailed(parse(url), "more than ${properties.maxRedirects} redirects")
    }

    /** POSTs [body] to [url] as [contentType]. A redirect is returned, not followed. */
    fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ): FetchResponse =
        send(parse(url), headers + ("Content-Type" to contentType), properties.maxBytes) {
            it.POST(HttpRequest.BodyPublishers.ofString(body))
        }

    private fun parse(url: String): URI =
        try {
            URI(url.trim())
        } catch (e: Exception) {
            throw FetchRefused(URI("about:invalid"), "not an address: $url")
        }

    private fun send(
        uri: URI,
        headers: Map<String, String>,
        maxBytes: Int,
        method: (HttpRequest.Builder) -> HttpRequest.Builder,
    ): FetchResponse {
        guard.check(uri)
        val builder =
            HttpRequest.newBuilder(uri)
                .timeout(properties.readTimeout)
                .header("User-Agent", properties.userAgent)
        headers.forEach { (name, value) -> builder.setHeader(name, value) }
        val response =
            try {
                client.send(method(builder).build(), HttpResponse.BodyHandlers.ofInputStream())
            } catch (e: HttpTimeoutException) {
                throw FetchFailed(uri, "timed out", e)
            } catch (e: IOException) {
                throw FetchFailed(uri, e.message ?: e.javaClass.simpleName, e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw FetchFailed(uri, "interrupted", e)
            }
        val (bytes, truncated) =
            try {
                response.body().use { input ->
                    val read = input.readNBytes(maxBytes)
                    read to (input.read() != -1)
                }
            } catch (e: IOException) {
                throw FetchFailed(uri, "reading the response: ${e.message}", e)
            }
        return FetchResponse(
            status = response.statusCode(),
            finalUri = uri,
            headers = response.headers(),
            body = String(bytes, charset(response.headers().firstValue("Content-Type").orElse(""))),
            truncated = truncated,
        )
    }

    private fun charset(contentType: String): Charset =
        CHARSET.find(contentType)?.groupValues?.get(1)?.let {
            runCatching { Charset.forName(it.trim('"', '\'')) }.getOrNull()
        } ?: Charsets.UTF_8

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        val CHARSET = Regex("""(?i)charset=([^;\s]+)""")
    }
}
