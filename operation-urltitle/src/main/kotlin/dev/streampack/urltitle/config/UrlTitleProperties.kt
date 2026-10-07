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
    /**
     * Sign-in and bot-check page titles that say nothing about the link. Matched ignoring case
     * against the whole title, or its first or last part around a site-name separator.
     */
    val suppressedTitles: List<String> =
        listOf(
            "Sign in",
            "Log in",
            "Login",
            "Login required",
            "Sign in to continue",
            "Access denied",
            "Forbidden",
            "Just a moment...",
            "Attention Required!",
            "Are you a robot?",
        ),
    val similarityThreshold: Double = 0.3,
    val connectTimeoutSeconds: Int = 5,
    val readTimeoutSeconds: Int = 10,
)
