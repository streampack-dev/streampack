/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.outgoing

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/** What happens to a published post's outgoing links (#112 mentions, #128 autosubscribe). */
@ConfigurationProperties(prefix = "streampack.blog.outgoing")
data class OutgoingLinksProperties(
    /** Look for posts whose links need handling at all. */
    val enabled: Boolean = true,
    /** Tell linked sites of the mention, by Webmention or Pingback (#112). */
    val mentions: Boolean = true,
    /** Ask the RSS reader to subscribe to linked sites' feeds (#128). */
    val autosubscribe: Boolean = true,
    /** How often to look for posts to handle. */
    val interval: Duration = Duration.ofMinutes(1),
    /** How many posts to handle in one look. */
    val batchSize: Int = 10,
    /** How many times a mention that failed on the way (a timeout, a 5xx) is tried again. */
    val retries: Int = 2,
    /** How long to wait before trying a mention again; doubled each time. */
    val retryDelay: Duration = Duration.ofSeconds(2),
    val userAgent: String = "ByteCode.News mentions (+https://bytecode.news)",
)
