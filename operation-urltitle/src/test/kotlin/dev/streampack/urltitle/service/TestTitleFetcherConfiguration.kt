/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.service

import dev.streampack.core.service.TitleFetchResult
import dev.streampack.core.service.TitleFetcher
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Provides a predictable TitleFetcher for tests that returns titles based on URL */
@TestConfiguration
class TestTitleFetcherConfiguration {

    @Bean @Primary fun testTitleFetcher(): TitleFetcher = TestTitleFetcher()
}

/** Returns controlled titles keyed by URL, allowing tests to verify summary formatting */
class TestTitleFetcher : TitleFetcher {
    private val titles = mutableMapOf<String, String>()
    private val invalidCertificates = mutableSetOf<String>()

    fun setTitle(url: String, title: String) {
        titles[url] = title
    }

    fun clear() {
        titles.clear()
        invalidCertificates.clear()
    }

    fun setInvalidCertificate(url: String) {
        invalidCertificates += url
    }

    override fun fetchTitle(url: String): String? = titles[url]

    override fun fetchTitleResult(url: String): TitleFetchResult {
        if (url in invalidCertificates) {
            return TitleFetchResult(
                title = null,
                finalUrl = url,
                certificateInvalid = true,
                warnings = listOf("TLS certificate validation failed"),
            )
        }
        return TitleFetchResult(title = titles[url], finalUrl = url)
    }
}
