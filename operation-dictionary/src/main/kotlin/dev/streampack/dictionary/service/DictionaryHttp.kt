/* Joseph B. Ottinger (C)2026 */
package dev.streampack.dictionary.service

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The dictionary providers' HTTP client. Unlike the shared page fetcher it keeps the status, so a
 * provider can tell "no such word" from "not answering", and its timeout is short
 * (`streampack.dictionary.timeout`, 3 seconds): a provider that hangs is given up on quickly and
 * the next one asked.
 */
@Component
class DictionaryHttp(
    @Value("\${streampack.dictionary.timeout:3s}") private val timeout: Duration,
) {
    private val client = HttpClient.newBuilder().connectTimeout(timeout).build()

    data class Response(val status: Int, val body: String)

    /** GETs [url]; throws when there's no answer in time */
    fun get(url: String): Response {
        val request =
            HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return Response(response.statusCode(), response.body())
    }

    companion object {
        private const val USER_AGENT = "streampack-define/1.0 (+https://bytecode.news)"

        /** [word] as one path segment: spaces as %20, not + */
        fun segment(word: String): String =
            URLEncoder.encode(word, Charsets.UTF_8).replace("+", "%20")
    }
}
