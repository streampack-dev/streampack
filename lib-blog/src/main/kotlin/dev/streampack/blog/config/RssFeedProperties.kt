/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Every post feed's channel (#233): `BLOG_FEED_TITLE` and `BLOG_FEED_DESCRIPTION`. A blank title is
 * the site's name.
 */
@ConfigurationProperties(prefix = "streampack.blog.feed")
data class RssFeedProperties(
    val title: String = "",
    val description: String = "JVM ecosystem news and community content",
    val language: String = "en-us",
    val itemCount: Int = 20,
)
