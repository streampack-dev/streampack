/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.service

/** Fetch and extract readable article content from a source URL. */
interface SuggestedContentFetcher {
    fun fetch(url: String): FetchOutcome
}

sealed interface FetchOutcome {
    data class Success(
        val requestedUrl: String,
        val finalUrl: String,
        val title: String,
        val extractedText: String,
        val warnings: List<String> = emptyList(),
    ) : FetchOutcome

    data class Failure(val message: String, val certificateInvalid: Boolean = false) : FetchOutcome
}
