/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Tag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class PostTagRepositoryTests {

    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var tagRepository: TagRepository

    private lateinit var testPost: Post
    private lateinit var testTag: Tag

    @BeforeEach
    fun setUp() {
        testPost =
            postRepository.save(
                Post(
                    title = "Tagged Post",
                    markdownSource = "content",
                    renderedHtml = "<p>content</p>",
                )
            )
        testTag = tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
    }

    @Test
    fun `assign tag to post`() {
        val pt = postTagRepository.save(PostTag(post = testPost, tag = testTag))
        val found = postTagRepository.findById(pt.id).orElse(null)

        assertNotNull(found)
        assertEquals(testPost.id, found.post.id)
        assertEquals(testTag.id, found.tag.id)
    }

    @Test
    fun `findByPost returns tags for post`() {
        val otherTag = tagRepository.save(Tag(name = "spring", slug = "spring"))
        postTagRepository.save(PostTag(post = testPost, tag = testTag))
        postTagRepository.save(PostTag(post = testPost, tag = otherTag))

        val results = postTagRepository.findByPost(testPost.id)
        assertEquals(2, results.size)
    }

    @Test
    fun `findByTag returns posts with tag`() {
        val otherPost =
            postRepository.save(
                Post(
                    title = "Another Post",
                    markdownSource = "other",
                    renderedHtml = "<p>other</p>",
                )
            )
        postTagRepository.save(PostTag(post = testPost, tag = testTag))
        postTagRepository.save(PostTag(post = otherPost, tag = testTag))

        val results = postTagRepository.findByTag(testTag.id)
        assertEquals(2, results.size)
    }

    @Test
    fun `unique constraint on post and tag pair`() {
        postTagRepository.save(PostTag(post = testPost, tag = testTag))
        postTagRepository.flush()

        assertThrows(Exception::class.java) {
            postTagRepository.save(PostTag(post = testPost, tag = testTag))
            postTagRepository.flush()
        }
    }
}
