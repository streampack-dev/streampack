/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.media

import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize

/**
 * Media storage (#196): where uploaded media (a post's audio) is kept, and how big a file may be.
 *
 * Environment-variable form: `STREAMPACK_MEDIA_DIR`, `STREAMPACK_MEDIA_MAX_SIZE` (`100MB`),
 * `STREAMPACK_MEDIA_CACHE_MAX_AGE` (`PT5M`).
 *
 * With no [dir], media is off: uploads answer 503, `/media/…` answers 404, and posts and feeds list
 * no versions. A [dir] that is set but can't be created or written stops the server at startup,
 * naming the directory, rather than failing each upload later.
 */
@ConfigurationProperties(prefix = "streampack.media")
data class MediaProperties(
    /** The directory media is stored in, on a mounted volume; blank turns media off. */
    val dir: String = "",
    /** The largest file an upload may carry; the multipart limits follow it. */
    val maxSize: DataSize = DataSize.ofMegabytes(100),
    /**
     * How long caches may keep a media file before asking again. Short, as a replaced file keeps
     * its address; its ETag changes, so a revalidation fetches the new one.
     */
    val cacheMaxAge: Duration = Duration.ofMinutes(5),
)
