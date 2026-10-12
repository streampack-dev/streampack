/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * The podcast feed's channel details (#196), for `/podcast.xml`: the posts with a recording, with
 * the `itunes:` tags podcast apps read. The values aren't decided yet; these are the defaults the
 * issue settled on (the site's name, no author, no artwork, no category, not explicit).
 */
@ConfigurationProperties(prefix = "streampack.blog.podcast")
data class PodcastProperties(
    /** The show's title; blank uses the site's name (`BLOG_SITE_NAME`). */
    val title: String = "",
    /** The show's description; blank says it's the posts of the site, read aloud. */
    val description: String = "",
    /** `itunes:author`; blank leaves it out. */
    val author: String = "",
    /** The artwork's absolute URL (`itunes:image`); blank leaves it out. */
    val image: String = "",
    /**
     * `itunes:category`, as Apple names it, optionally with a subcategory after a slash
     * (`Technology` or `News/Tech News`); blank leaves it out.
     */
    val category: String = "",
    /** `itunes:explicit`. */
    val explicit: Boolean = false,
    /**
     * `itunes:owner`'s name and email: who podcast directories contact about the show (Apple mails
     * the address to confirm a submission). Both blank leave it out.
     */
    val ownerName: String = "",
    val ownerEmail: String = "",
    /** The feed's language. */
    val language: String = "en-us",
)
