/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.bootstrap

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.User
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class RenderedHtmlSanitizationBackfillTests {

    @Autowired lateinit var backfill: RenderedHtmlSanitizationBackfill
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository

    @Test
    fun `sanitizes stored post and comment html and leaves clean rows untouched`() {
        val now = Instant.now()
        val author =
            userRepository.save(
                User(
                    username = "backfill-author",
                    email = "backfill-author@example.com",
                    emailVerified = true,
                    displayName = "Backfill Author",
                )
            )
        val hostile =
            postRepository.save(
                Post(
                    title = "Hostile",
                    markdownSource = "[x](javascript:alert(1))",
                    renderedHtml = "<p><a href=\"javascript:alert(1)\">x</a></p>",
                    status = PostStatus.APPROVED,
                    publishedAt = now,
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        val clean =
            postRepository.save(
                Post(
                    title = "Clean",
                    markdownSource = "# Clean",
                    renderedHtml = "<h1>Clean</h1>",
                    status = PostStatus.APPROVED,
                    publishedAt = now,
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        val hostileComment =
            commentRepository.save(
                Comment(
                    post = clean,
                    author = author,
                    markdownSource = "<img>",
                    renderedHtml = "<p><img src=\"x\" onerror=\"steal()\"></p>",
                    createdAt = now,
                    updatedAt = now,
                )
            )

        backfill.run(DefaultApplicationArguments())

        val hostileAfter = postRepository.findById(hostile.id).orElseThrow()
        assertFalse(hostileAfter.renderedHtml.contains("javascript:"), hostileAfter.renderedHtml)
        assertTrue(hostileAfter.renderedHtml.contains("x"), hostileAfter.renderedHtml)

        val cleanAfter = postRepository.findById(clean.id).orElseThrow()
        assertEquals("<h1>Clean</h1>", cleanAfter.renderedHtml)
        assertEquals(clean.updatedAt, cleanAfter.updatedAt)

        val commentAfter = commentRepository.findById(hostileComment.id).orElseThrow()
        assertFalse(commentAfter.renderedHtml.contains("onerror"), commentAfter.renderedHtml)
    }
}
