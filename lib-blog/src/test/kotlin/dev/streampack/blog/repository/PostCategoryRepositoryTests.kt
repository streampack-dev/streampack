/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
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
class PostCategoryRepositoryTests {

    @Autowired lateinit var postCategoryRepository: PostCategoryRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var categoryRepository: CategoryRepository

    private lateinit var testPost: Post
    private lateinit var testCategory: Category

    @BeforeEach
    fun setUp() {
        testPost =
            postRepository.save(
                Post(
                    title = "Categorized Post",
                    markdownSource = "content",
                    renderedHtml = "<p>content</p>",
                )
            )
        testCategory = categoryRepository.save(Category(name = "JVM", slug = "jvm"))
    }

    @Test
    fun `assign category to post`() {
        val pc = postCategoryRepository.save(PostCategory(post = testPost, category = testCategory))
        val found = postCategoryRepository.findById(pc.id).orElse(null)

        assertNotNull(found)
        assertEquals(testPost.id, found.post.id)
        assertEquals(testCategory.id, found.category.id)
    }

    @Test
    fun `findByPost returns categories for post`() {
        val otherCategory = categoryRepository.save(Category(name = "Spring", slug = "spring"))
        postCategoryRepository.save(PostCategory(post = testPost, category = testCategory))
        postCategoryRepository.save(PostCategory(post = testPost, category = otherCategory))

        val results = postCategoryRepository.findByPost(testPost.id)
        assertEquals(2, results.size)
    }

    @Test
    fun `findByCategory returns posts in category`() {
        val otherPost =
            postRepository.save(
                Post(
                    title = "Another Post",
                    markdownSource = "other",
                    renderedHtml = "<p>other</p>",
                )
            )
        postCategoryRepository.save(PostCategory(post = testPost, category = testCategory))
        postCategoryRepository.save(PostCategory(post = otherPost, category = testCategory))

        val results = postCategoryRepository.findByCategory(testCategory.id)
        assertEquals(2, results.size)
    }

    @Test
    fun `unique constraint on post and category pair`() {
        postCategoryRepository.save(PostCategory(post = testPost, category = testCategory))
        postCategoryRepository.flush()

        assertThrows(Exception::class.java) {
            postCategoryRepository.save(PostCategory(post = testPost, category = testCategory))
            postCategoryRepository.flush()
        }
    }
}
