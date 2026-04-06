/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class SlugRepositoryTests {

    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var postRepository: PostRepository

    private lateinit var testPost: Post

    @BeforeEach
    fun setUp() {
        testPost =
            postRepository.save(
                Post(title = "Test Post", markdownSource = "# Test", renderedHtml = "<h1>Test</h1>")
            )
    }

    @Test
    fun `save and retrieve slug`() {
        val slug =
            slugRepository.save(Slug(path = "2026/02/test-post", post = testPost, canonical = true))
        val found = slugRepository.findById(slug.id).orElse(null)

        assertNotNull(found)
        assertEquals("2026/02/test-post", found.path)
        assertEquals(true, found.canonical)
    }

    @Test
    fun `resolve finds slug by path`() {
        slugRepository.save(Slug(path = "2026/02/test-post", post = testPost, canonical = true))

        val found = slugRepository.resolve("2026/02/test-post")
        assertNotNull(found)
        assertEquals(testPost.id, found!!.post.id)
    }

    @Test
    fun `resolve returns null for nonexistent path`() {
        assertNull(slugRepository.resolve("nonexistent/path"))
    }

    @Test
    fun `unique constraint on path`() {
        val otherPost =
            postRepository.save(
                Post(title = "Other Post", markdownSource = "other", renderedHtml = "<p>other</p>")
            )
        slugRepository.save(Slug(path = "2026/02/test-post", post = testPost, canonical = true))
        slugRepository.flush()

        assertThrows(Exception::class.java) {
            slugRepository.save(
                Slug(path = "2026/02/test-post", post = otherPost, canonical = false)
            )
            slugRepository.flush()
        }
    }

    @Test
    fun `findByPost returns all slugs for a post`() {
        slugRepository.save(Slug(path = "2026/02/test-post", post = testPost, canonical = true))
        slugRepository.save(Slug(path = "old-slug", post = testPost, canonical = false))

        val slugs = slugRepository.findByPost(testPost.id)
        assertEquals(2, slugs.size)
    }

    @Test
    fun `findCanonical returns only canonical slug`() {
        slugRepository.save(Slug(path = "2026/02/test-post", post = testPost, canonical = true))
        slugRepository.save(Slug(path = "old-slug", post = testPost, canonical = false))

        val canonical = slugRepository.findCanonical(testPost.id)
        assertNotNull(canonical)
        assertEquals("2026/02/test-post", canonical!!.path)
        assertEquals(true, canonical.canonical)
    }

    @Test
    fun `multiple slugs per post`() {
        slugRepository.save(Slug(path = "slug-one", post = testPost, canonical = true))
        slugRepository.save(Slug(path = "slug-two", post = testPost, canonical = false))
        slugRepository.save(Slug(path = "slug-three", post = testPost, canonical = false))

        val slugs = slugRepository.findByPost(testPost.id)
        assertEquals(3, slugs.size)
    }
}
