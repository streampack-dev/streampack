/* Joseph B. Ottinger (C)2026 */
package dev.streampack.urltitle.config

import dev.streampack.core.model.Protocol
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "streampack.urltitle")
data class UrlTitleProperties(
    val protocols: List<Protocol> =
        listOf(
            Protocol.IRC,
            Protocol.DISCORD,
            Protocol.SLACK,
            Protocol.CONSOLE,
            Protocol.MATTERMOST,
        ),
    val defaultIgnoredHosts: List<String> =
        listOf("bpa.st", "dpaste.com", "pastebin.com", "pastebin.org", "twitter.com", "x.com"),
    val similarityThreshold: Double = 0.3,
    val connectTimeoutSeconds: Int = 5,
    val readTimeoutSeconds: Int = 10,
)
