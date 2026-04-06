/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.service

import dev.streampack.core.extensions.compress
import dev.streampack.core.service.PageFetcher
import org.jsoup.Jsoup
import org.springframework.stereotype.Component

/** HTTP implementation of SuggestedContentFetcher with simple article extraction heuristics. */
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

        val document = Jsoup.parse(body, finalUrl)
        document.select("script, style, noscript").remove()

        val title =
            document.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
                ?: document.select("meta[name=twitter:title]").attr("content").takeIf {
                    it.isNotBlank()
                }
                ?: document.title().takeIf { it.isNotBlank() }
                ?: finalUrl

        val article = document.selectFirst("article")?.text().orEmpty().compress()
        val main = document.selectFirst("main")?.text().orEmpty().compress()
        val bodyText = document.body().text().compress()
        val extracted = sequenceOf(article, main, bodyText).maxByOrNull { it.length }.orEmpty()

        if (extracted.isBlank()) {
            return FetchOutcome.Failure("Could not extract readable content from the page")
        }

        return FetchOutcome.Success(
            requestedUrl = url,
            finalUrl = finalUrl,
            title = title.trim(),
            extractedText = extracted.take(18000),
            warnings = warnings,
        )
    }
}
