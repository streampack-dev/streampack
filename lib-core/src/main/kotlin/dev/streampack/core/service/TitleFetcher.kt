/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.service

/** Abstraction for retrieving the HTML title of a URL */
interface TitleFetcher {
    /** Returns the page title for the given URL, or null on any failure */
    fun fetchTitle(url: String): String?

    /** Returns structured title fetch details; default adapts the legacy `fetchTitle` API. */
    fun fetchTitleResult(url: String): TitleFetchResult {
        return TitleFetchResult(title = fetchTitle(url), finalUrl = url)
    }
}
