/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/** Abstraction for retrieving the body of a URL as a string */
interface PageFetcher {
    /** Returns the page body for the given URL, or null on any failure */
    fun fetch(url: String): String?

    /** Returns structured fetch details; default adapts the legacy `fetch` API. */
    fun fetchResult(url: String): PageFetchResult {
        val body = fetch(url)
        return PageFetchResult(body = body, finalUrl = url)
    }
}
