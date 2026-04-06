/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class SlugGenerationServiceTests {

    @Autowired lateinit var slugGenerationService: SlugGenerationService
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var postRepository: PostRepository

    private val feb2026 = ZonedDateTime.of(2026, 2, 15, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()

    @Test
    fun `normal title produces correct year-month-slug format`() {
        val result = slugGenerationService.generateSlug("Hello World", feb2026)
        assertEquals("2026/02/hello-world", result)
    }

    @Test
    fun `special characters stripped and replaced`() {
        val result = slugGenerationService.generateSlug("What's New in Spring Boot 4.0?!", feb2026)
        assertEquals("2026/02/what-s-new-in-spring-boot-4-0", result)
    }

    @Test
    fun `consecutive hyphens collapsed`() {
        val result = slugGenerationService.generateSlug("Hello --- World", feb2026)
        assertEquals("2026/02/hello-world", result)
    }

    @Test
    fun `leading and trailing hyphens trimmed`() {
        val result = slugGenerationService.generateSlug("--Hello World--", feb2026)
        assertEquals("2026/02/hello-world", result)
    }

    @Test
    fun `uppercase converted to lowercase`() {
        val result = slugGenerationService.generateSlug("KOTLIN IS AWESOME", feb2026)
        assertEquals("2026/02/kotlin-is-awesome", result)
    }

    @Test
    fun `duplicate title produces suffixed slug`() {
        val post =
            postRepository.save(
                Post(title = "Test", markdownSource = "md", renderedHtml = "<p>md</p>")
            )
        slugRepository.save(Slug(path = "2026/02/hello-world", post = post, canonical = true))
        slugRepository.flush()

        val result = slugGenerationService.generateSlug("Hello World", feb2026)
        assertEquals("2026/02/hello-world-2", result)
    }

    @Test
    fun `multiple collisions increment suffix`() {
        val post =
            postRepository.save(
                Post(title = "Test", markdownSource = "md", renderedHtml = "<p>md</p>")
            )
        slugRepository.save(Slug(path = "2026/02/hello-world", post = post, canonical = true))
        slugRepository.save(Slug(path = "2026/02/hello-world-2", post = post, canonical = false))
        slugRepository.flush()

        val result = slugGenerationService.generateSlug("Hello World", feb2026)
        assertEquals("2026/02/hello-world-3", result)
    }

    @Test
    fun `different month produces different slug prefix`() {
        val jan2026 = ZonedDateTime.of(2026, 1, 5, 12, 0, 0, 0, ZoneOffset.UTC).toInstant()
        val result = slugGenerationService.generateSlug("Hello World", jan2026)
        assertEquals("2026/01/hello-world", result)
    }

    @Test
    fun `bare slug has no date prefix`() {
        val result = slugGenerationService.generateBareSlug("About Us")
        assertEquals("about-us", result)
    }

    @Test
    fun `bare slug handles collision with suffix`() {
        val post =
            postRepository.save(
                Post(title = "Test", markdownSource = "md", renderedHtml = "<p>md</p>")
            )
        slugRepository.save(Slug(path = "about", post = post, canonical = true))
        slugRepository.flush()

        val result = slugGenerationService.generateBareSlug("About")
        assertEquals("about-2", result)
    }
}
