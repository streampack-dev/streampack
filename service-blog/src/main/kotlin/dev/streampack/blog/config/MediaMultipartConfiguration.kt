/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.config

import dev.streampack.blog.media.MediaProperties
import jakarta.servlet.MultipartConfigElement
import org.springframework.boot.servlet.MultipartConfigFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.util.unit.DataSize

/**
 * Multipart limits that follow the media size limit (#196), so `STREAMPACK_MEDIA_MAX_SIZE` is the
 * one setting: a file may be that large, and a request a megabyte more, for its other fields. Parts
 * over a megabyte are written to a temporary file rather than held in memory. Anything larger is
 * refused (413) before a controller sees it; the upload endpoint checks the size again itself.
 */
@Configuration
class MediaMultipartConfiguration {
    @Bean
    fun multipartConfigElement(media: MediaProperties): MultipartConfigElement {
        val factory = MultipartConfigFactory()
        factory.setMaxFileSize(media.maxSize)
        factory.setMaxRequestSize(DataSize.ofBytes(media.maxSize.toBytes() + REQUEST_OVERHEAD))
        factory.setFileSizeThreshold(DataSize.ofMegabytes(1))
        return factory.createMultipartConfig()
    }

    companion object {
        private const val REQUEST_OVERHEAD = 1024L * 1024
    }
}
