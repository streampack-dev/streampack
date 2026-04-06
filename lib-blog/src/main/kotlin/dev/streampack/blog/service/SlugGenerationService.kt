/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.repository.SlugRepository
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.stereotype.Service

/** Generates unique URL-safe slug paths with year/month prefix */
@Service
class SlugGenerationService(private val slugRepository: SlugRepository) {

    /** Generate a unique slug path for a post title */
    fun generateSlug(title: String, createdAt: Instant): String {
        val dateTime = createdAt.atOffset(ZoneOffset.UTC)
        val year = dateTime.year.toString()
        val month = "%02d".format(dateTime.monthValue)
        val slugified = slugify(title)
        val basePath = "$year/$month/$slugified"

        // Check for uniqueness and append suffix on collision
        if (slugRepository.resolve(basePath) == null) return basePath

        var suffix = 2
        while (true) {
            val candidate = "$basePath-$suffix"
            if (slugRepository.resolve(candidate) == null) return candidate
            suffix++
        }
    }

    /** Generate a unique bare slug without date prefix, for system category posts */
    fun generateBareSlug(title: String): String {
        val basePath = slugify(title)

        if (slugRepository.resolve(basePath) == null) return basePath

        var suffix = 2
        while (true) {
            val candidate = "$basePath-$suffix"
            if (slugRepository.resolve(candidate) == null) return candidate
            suffix++
        }
    }

    /** Convert a title to a URL-safe slug segment */
    fun slugify(title: String): String {
        return title
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-") // non-alphanumeric to hyphens
            .replace(Regex("-+"), "-") // collapse consecutive hyphens
            .trim('-') // remove leading/trailing hyphens
    }
}
