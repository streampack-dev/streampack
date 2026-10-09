/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.service

import dev.streampack.core.fetch.ArticleText
import dev.streampack.core.service.PageFetcher
import org.springframework.stereotype.Component

/** HTTP implementation of SuggestedContentFetcher, extracting the text with [ArticleText]. */
@Component
class HttpSuggestedContentFetcher(private val pageFetcher: PageFetcher) : SuggestedContentFetcher {

    override fun fetch(url: String): FetchOutcome {
        val fetchResult = pageFetcher.fetchResult(url)
        val body = fetchResult.body
        if (body.isNullOrBlank()) {
            if (fetchResult.certificateInvalid) {
                return FetchOutcome.Failure(
                    message = "TLS certificate validation failed while fetching the URL",
                    certificateInvalid = true,
                )
            }
            return FetchOutcome.Failure(
                fetchResult.warnings.firstOrNull() ?: "Fetched page body was empty"
            )
        }

        val finalUrl = fetchResult.finalUrl ?: url
        val warnings = fetchResult.warnings.toMutableList()

        val extracted =
            ArticleText.extract(body, finalUrl)
                ?: return FetchOutcome.Failure("Could not extract readable content from the page")

        return FetchOutcome.Success(
            requestedUrl = url,
            finalUrl = finalUrl,
            title = extracted.title,
            extractedText = extracted.text.take(18000),
            warnings = warnings,
        )
    }
}
