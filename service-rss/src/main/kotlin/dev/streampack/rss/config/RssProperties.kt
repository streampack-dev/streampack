/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.config

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Feed polling runs in bounded batches (issue #40): every [schedulerInterval] the poller takes the
 * [batchSize] oldest-due feeds; a polled feed is next due [pollInterval] later, or later still
 * after failures, up to [maxBackoff]. Fetches go through the guarded fetcher, whose timeouts are
 * `streampack.fetch.*`. [autosubscribe] governs following the sites published posts link to (#128).
 */
@ConfigurationProperties(prefix = "streampack.rss")
data class RssProperties(
    val pollInterval: Duration = Duration.ofHours(1),
    val schedulerInterval: Duration = Duration.ofSeconds(90),
    val batchSize: Int = 5,
    val maxBackoff: Duration = Duration.ofDays(1),
    val autosubscribe: Autosubscribe = Autosubscribe(),
) {
    /**
     * Sites never subscribed to because a post linked them: [skipHosts], each with its subdomains.
     * Code hosts, video, encyclopedias, documentation and specs; not `github.io`, where many
     * personal blogs are.
     */
    data class Autosubscribe(
        val skipHosts: List<String> =
            listOf(
                "github.com",
                "gitlab.com",
                "bitbucket.org",
                "youtube.com",
                "youtu.be",
                "vimeo.com",
                "twitch.tv",
                "wikipedia.org",
                "wikimedia.org",
                "openjdk.org",
                "jcp.org",
                "java.com",
                "docs.oracle.com",
                "docs.spring.io",
                "spring.io",
                "kotlinlang.org",
                "maven.apache.org",
                "search.maven.org",
                "central.sonatype.com",
                "npmjs.com",
                "stackoverflow.com",
                "stackexchange.com",
                "x.com",
                "twitter.com",
                "reddit.com",
                "news.ycombinator.com",
                "medium.com",
                "linkedin.com",
                "facebook.com",
                "bsky.app",
                "mastodon.social",
                "google.com",
                "goo.gl",
                "bit.ly",
                "amazon.com",
                "w3.org",
                "ietf.org",
                "rfc-editor.org",
            )
    )
}
