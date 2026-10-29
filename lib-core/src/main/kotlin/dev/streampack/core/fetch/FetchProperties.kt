/* Joseph B. Ottinger (C)2026 */
package dev.streampack.core.fetch

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * How [GuardedFetcher] fetches addresses someone else chose: a post's links, a feed to discover.
 *
 * [allowLoopback] and [allowPrivate] are for tests (whose servers are on localhost) and local
 * development; in production both stay off.
 */
@ConfigurationProperties(prefix = "streampack.fetch")
data class FetchProperties(
    /** Allow 127.0.0.0/8 and ::1. */
    val allowLoopback: Boolean = false,
    /** Allow private ranges: 10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7. */
    val allowPrivate: Boolean = false,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    /** How long a whole request may take, the body included (#296). */
    val readTimeout: Duration = Duration.ofSeconds(10),
    /** The most of a response body that is read; the rest is left unread. */
    val maxBytes: Int = 1_048_576,
    val maxRedirects: Int = 5,
    val userAgent: String = "ByteCode.News (+https://bytecode.news)",
    /**
     * The user agent for page fetches: URL titles, suggested content, spec lookups (#292). A
     * current browser's, since some sites refuse agents that look like bots; it goes stale, so set
     * it when Chrome has moved on.
     */
    val pageUserAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36",
)
