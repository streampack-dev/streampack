/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import java.net.URI
import java.net.http.HttpHeaders

/**
 * A response from [GuardedFetcher]: its status, where it came from after redirects, its headers,
 * and as much of its body as was read ([truncated] when there was more).
 */
data class FetchResponse(
    val status: Int,
    val finalUri: URI,
    val headers: HttpHeaders,
    val body: String,
    val truncated: Boolean = false,
) {
    val successful: Boolean
        get() = status in 200..299

    fun header(name: String): String? = headers.firstValue(name).orElse(null)

    fun headers(name: String): List<String> = headers.allValues(name)
}
