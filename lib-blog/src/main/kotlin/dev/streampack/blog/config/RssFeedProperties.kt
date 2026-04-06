/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** RSS feed channel metadata configuration */
@ConfigurationProperties(prefix = "streampack.blog.feed")
data class RssFeedProperties(
    val title: String = "bytecode.news",
    val description: String = "JVM ecosystem news and community content",
    val language: String = "en-us",
    val itemCount: Int = 20,
)
