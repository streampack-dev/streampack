/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.service

import dev.streampack.core.extensions.compress
import dev.streampack.core.service.TitleFetchResult
import dev.streampack.core.service.TitleFetcher
import dev.streampack.urltitle.config.UrlTitleProperties
import dev.streampack.urltitle.entity.IgnoredHost
import dev.streampack.urltitle.repository.IgnoredHostRepository
import java.net.URI
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UrlTitleService(
    private val ignoredHostRepository: IgnoredHostRepository,
    private val properties: UrlTitleProperties,
    var titleFetcher: TitleFetcher,
) : InitializingBean {

    private val logger = LoggerFactory.getLogger(UrlTitleService::class.java)

    private val urlPattern = Regex("https?://\\S+")

    private val suppressedTitles: Set<String> by lazy {
        properties.suppressedTitles.map { normalizeTitle(it) }.toSet()
    }

    /** Seeds default ignored hosts from configuration on startup */
    @Transactional
    override fun afterPropertiesSet() {
        properties.defaultIgnoredHosts.forEach { entry ->
            val normalized = normalizeEntry(entry)
            if (ignoredHostRepository.findByHostNameIgnoreCase(normalized) == null) {
                ignoredHostRepository.save(IgnoredHost(hostName = normalized))
            }
        }
    }

    /** Delegates to the TitleFetcher to retrieve the page title */
    fun fetchTitle(url: String): String? = titleFetcher.fetchTitle(url)

    /** Delegates to the TitleFetcher and includes trust metadata for warning output. */
    fun fetchTitleResult(url: String): TitleFetchResult = titleFetcher.fetchTitleResult(url)

    /** Computes Jaccard similarity between URL path tokens and title tokens */
    fun calculateJaccardSimilarity(url: String, title: String): Double {
        val cleanedUrl = cleanUrl(url)
        val urlWords = tokenize(cleanedUrl)
        val titleWords = tokenize(title)

        val intersection = urlWords.intersect(titleWords).size
        val union = urlWords.union(titleWords).size

        return if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
    }

    /** Extracts all URLs from text, cleaning trailing sentence punctuation */
    fun extractUrls(text: String): List<String> {
        return urlPattern
            .findAll(text)
            .map { it.value.trimEnd('.', ',', ')', '>', ';', ':', '!', '?') }
            .filter { it.isNotBlank() }
            .toList()
    }

    /**
     * Checks whether a URL is covered by an ignore-list entry: a host, a `*.` subdomain wildcard,
     * or either of those with a path prefix. The list is a few dozen rows at most, so it's matched
     * in memory rather than with pattern queries.
     */
    @Transactional(readOnly = true)
    fun isIgnoredHost(url: String): Boolean {
        val uri =
            try {
                URI(url)
            } catch (_: Exception) {
                return false
            }
        val host = normalizeHost(uri.host ?: return false)
        val path = (uri.path ?: "").lowercase()
        return ignoredHostRepository.findAll().any { entryMatches(it.hostName, host, path) }
    }

    /**
     * True when a fetched title belongs to a sign-in or bot-check page rather than the linked
     * content. Such a title is the whole title, or the first or last part around a site-name
     * separator ("Repopack · Sign in", "Sign in · GitHub"), so a real title that merely mentions
     * signing in still gets through.
     */
    fun isSuppressedTitle(title: String): Boolean {
        if (suppressedTitles.isEmpty()) return false
        val whole = normalizeTitle(title)
        if (whole in suppressedTitles) return true
        val parts = whole.split(titleSeparator).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return false
        return parts.first() in suppressedTitles || parts.last() in suppressedTitles
    }

    /** Adds an entry and returns its normalized form, which is what `url ignore list` shows */
    @Transactional
    fun addIgnoredHost(entry: String): String {
        val normalized = normalizeEntry(entry)
        if (ignoredHostRepository.findByHostNameIgnoreCase(normalized) == null) {
            ignoredHostRepository.save(IgnoredHost(hostName = normalized))
        }
        return normalized
    }

    /** Removes an entry and returns its normalized form, or null if it wasn't in the list */
    @Transactional
    fun deleteIgnoredHost(entry: String): String? {
        val normalized = normalizeEntry(entry)
        val existing = ignoredHostRepository.findByHostNameIgnoreCase(normalized) ?: return null
        ignoredHostRepository.delete(existing)
        return normalized
    }

    @Transactional(readOnly = true)
    fun findAllIgnoredHosts(): List<String> {
        return ignoredHostRepository.findAll().map { it.hostName }
    }

    companion object {
        // The width of ignored_hosts.host_name; a host plus a path prefix fits comfortably.
        private const val MAX_ENTRY_LENGTH = 255

        private val schemePattern = Regex("^[a-z][a-z0-9+.-]*://")
        private val hostPattern = Regex("^(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)*$")

        // Separators sites put between the page and site name. A hyphen counts only with spaces
        // around it, so "Log-in help" stays one phrase.
        private val titleSeparator = Regex("\\s+-\\s+|[·|—–]")

        /** Strips www. prefix and lowercases for consistent ignore-list matching */
        fun normalizeHost(host: String): String = host.lowercase().removePrefix("www.")

        /**
         * Normalizes an ignore-list entry: lower-cased, with no scheme, `www.`, query, fragment or
         * trailing slash. The path is lower-cased too and URLs are matched ignoring case, because
         * an ignore list is better off over-matching `/Project` than missing it.
         */
        fun normalizeEntry(entry: String): String {
            val value =
                entry
                    .trim()
                    .lowercase()
                    .replace(schemePattern, "")
                    .substringBefore('#')
                    .substringBefore('?')
                    .trimEnd('/')
            val host = normalizeHost(value.substringBefore('/'))
            val path = if ('/' in value) "/" + value.substringAfter('/').trim('/') else ""
            require(hostPattern.matches(host)) {
                "'$entry' is not a host, *.host, or host/path entry"
            }
            // "*.com" would silence every .com link; a wildcard needs a real domain under it.
            require(!host.startsWith("*.") || '.' in host.removePrefix("*.")) {
                "'$entry' is too broad; use *.example.com rather than *.com"
            }
            require(path.none { it.isWhitespace() }) { "'$entry' contains whitespace" }
            val normalized = host + path
            require(normalized.length <= MAX_ENTRY_LENGTH) {
                "'$entry' is longer than $MAX_ENTRY_LENGTH characters"
            }
            return normalized
        }

        /**
         * Whether a stored entry covers a URL's normalized host and lower-cased path.
         *
         * `*.host` covers the bare host as well as its subdomains: whoever writes it means
         * "anything on that site", and `www.` is already folded into the bare host, so excluding it
         * would only surprise. Path prefixes match whole segments: `/project` covers `/project` and
         * `/project/x`, not `/projects`.
         */
        fun entryMatches(entry: String, host: String, path: String): Boolean {
            val entryHost = entry.substringBefore('/')
            val hostMatches =
                if (entryHost.startsWith("*.")) {
                    val base = entryHost.removePrefix("*.")
                    host == base || host.endsWith(".$base")
                } else {
                    host == entryHost
                }
            if (!hostMatches) return false
            if ('/' !in entry) return true
            val prefix = "/" + entry.substringAfter('/')
            return path == prefix || path.startsWith("$prefix/")
        }

        /** Lower-cases, folds the ellipsis character, and collapses whitespace for comparison */
        fun normalizeTitle(title: String): String =
            title.lowercase().replace("…", "...").compress().trim()

        fun tokenize(text: String): Set<String> {
            return text.lowercase().split("\\W+".toRegex()).filter { it.isNotEmpty() }.toSet()
        }

        fun extractHost(url: String): String {
            return try {
                URI(url).host ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        /** Strips protocol, www, file extensions, numbers, and converts separators to spaces */
        fun cleanUrl(url: String): String =
            url.replace("https://", "")
                .replace("http://", "")
                .replace("www", "")
                .replace(".", " ")
                .replace("-", " ")
                .replace("index", "")
                .replace("html", "")
                .replace("htm", "")
                .replace("/", " ")
                .replace(Regex("[0-9]+"), "")
                .compress()

        /** Produces a human-readable fallback title from URL path/host tokens. */
        fun deriveTitleFromUrl(url: String): String {
            val cleaned = cleanUrl(url)
            if (cleaned.isBlank()) return url
            return cleaned
                .split(" ")
                .filter { it.isNotBlank() }
                .joinToString(" ") { token ->
                    token.replaceFirstChar { c ->
                        if (c.isLowerCase()) c.titlecase() else c.toString()
                    }
                }
        }
    }
}
