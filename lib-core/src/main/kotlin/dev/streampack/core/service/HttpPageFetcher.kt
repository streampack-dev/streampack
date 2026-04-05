/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Default PageFetcher that retrieves page content over HTTP, handling gzip responses */
@Component
class HttpPageFetcher : PageFetcher {
    private val logger = LoggerFactory.getLogger(HttpPageFetcher::class.java)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override fun fetch(url: String): String? = fetchResult(url).body

    override fun fetchResult(url: String): PageFetchResult {
        return try {
            val parsed = URI(url)
            val scheme = parsed.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                logger.warn("Refusing to fetch non-http(s) URL: {}", url)
                return PageFetchResult(finalUrl = url, warnings = listOf("Refused non-http(s) URL"))
            }

            val client =
                HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
            val request =
                HttpRequest.newBuilder()
                    .uri(parsed)
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", USER_AGENT)
                    .header(
                        "Accept",
                        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    )
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Accept-Encoding", "gzip")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val finalUri = response.uri()

            if (response.statusCode() !in 200..299) {
                logger.warn(
                    "HTTP {} fetching {} (final URI: {})",
                    response.statusCode(),
                    url,
                    finalUri,
                )
                return PageFetchResult(
                    finalUrl = finalUri.toString(),
                    statusCode = response.statusCode(),
                    warnings = listOf("HTTP ${response.statusCode()} while fetching URL"),
                )
            }

            val encoding = response.headers().firstValue("Content-Encoding").orElse("")
            val inputStream: InputStream =
                if (encoding.equals("gzip", ignoreCase = true)) {
                    GZIPInputStream(response.body())
                } else {
                    response.body()
                }

            val body = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            logger.debug(
                "Fetched {} -> {} (status={}, encoding={}, body={} chars)",
                url,
                finalUri,
                response.statusCode(),
                encoding.ifEmpty { "none" },
                body.length,
            )
            val warnings = mutableListOf<String>()
            if (finalUri.scheme.equals("http", ignoreCase = true)) {
                warnings += "Warning: source resolved to plain HTTP without TLS."
            }
            PageFetchResult(
                body = body,
                finalUrl = finalUri.toString(),
                statusCode = response.statusCode(),
                warnings = warnings,
            )
        } catch (e: Exception) {
            if (isTlsFailure(e)) {
                logger.warn("TLS/certificate validation failed for {}: {}", url, e.message)
                return PageFetchResult(
                    finalUrl = url,
                    certificateInvalid = true,
                    warnings = listOf("TLS certificate validation failed"),
                )
            }
            logger.warn("Failed to fetch {}: {}", url, e.message)
            PageFetchResult(finalUrl = url, warnings = listOf("Failed to fetch URL: ${e.message}"))
        }
    }

    private fun isTlsFailure(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SSLHandshakeException || cause is SSLException) return true
            val name = cause::class.java.name
            if (name.contains("Certificate", ignoreCase = true)) return true
            cause = cause.cause
        }
        return false
    }
}
