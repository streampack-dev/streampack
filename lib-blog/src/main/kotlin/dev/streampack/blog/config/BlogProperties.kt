/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Configuration for the blog service module */
@ConfigurationProperties(prefix = "streampack.blog")
data class BlogProperties(
    val serviceId: String = "blog-service",
    val anonymousSubmission: Boolean = false,
    val siteName: String = "Nevet",
    val baseUrl: String = "http://localhost:3001",
)
