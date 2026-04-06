/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.repository

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.core.entity.User
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class CommentRepositoryTests {

    @Autowired lateinit var commentRepository: CommentRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var testPost: Post
    private lateinit var testUser: User

    @BeforeEach
    fun setUp() {
        testUser =
            userRepository.save(
                User(
                    username = "commenter",
                    email = "commenter@test.com",
                    displayName = "Test Commenter",
                )
            )
        testPost =
            postRepository.save(
                Post(
                    title = "Commented Post",
                    markdownSource = "content",
                    renderedHtml = "<p>content</p>",
                )
            )
    }

    @Test
    fun `save and retrieve comment`() {
        val comment =
            commentRepository.save(
                Comment(
                    post = testPost,
                    author = testUser,
                    markdownSource = "Great post!",
                    renderedHtml = "<p>Great post!</p>",
                )
            )
        val found = commentRepository.findById(comment.id).orElse(null)

        assertNotNull(found)
        assertEquals("Great post!", found.markdownSource)
    }

    @Test
    fun `findByPost returns all comments including deleted`() {
        commentRepository.save(
            Comment(
                post = testPost,
                author = testUser,
                markdownSource = "Visible",
                renderedHtml = "<p>Visible</p>",
            )
        )
        commentRepository.save(
            Comment(
                post = testPost,
                author = testUser,
                markdownSource = "Deleted",
                renderedHtml = "<p>Deleted</p>",
                deleted = true,
            )
        )

        val comments = commentRepository.findByPost(testPost.id)
        assertEquals(2, comments.size)
    }

    @Test
    fun `findByPost orders by createdAt`() {
        val now = Instant.now()
        commentRepository.save(
            Comment(
                post = testPost,
                author = testUser,
                markdownSource = "Second",
                renderedHtml = "<p>Second</p>",
                createdAt = now.plus(1, ChronoUnit.HOURS),
            )
        )
        commentRepository.save(
            Comment(
                post = testPost,
                author = testUser,
                markdownSource = "First",
                renderedHtml = "<p>First</p>",
                createdAt = now,
            )
        )

        val comments = commentRepository.findByPost(testPost.id)
        assertEquals(2, comments.size)
        assertEquals("First", comments[0].markdownSource)
        assertEquals("Second", comments[1].markdownSource)
    }

    @Test
    fun `findByAuthor excludes deleted comments`() {
        commentRepository.save(
            Comment(
                post = testPost,
                author = testUser,
                markdownSource = "Keep this",
                renderedHtml = "<p>Keep this</p>",
            )
        )
        commentRepository.save(
            Comment(
                post = testPost,
                author = testUser,
                markdownSource = "Remove this",
                renderedHtml = "<p>Remove this</p>",
                deleted = true,
            )
        )

        val comments = commentRepository.findByAuthor(testUser.id)
        assertEquals(1, comments.size)
        assertEquals("Keep this", comments[0].markdownSource)
    }

    @Test
    fun `nested comment with parentComment`() {
        val parent =
            commentRepository.save(
                Comment(
                    post = testPost,
                    author = testUser,
                    markdownSource = "Parent",
                    renderedHtml = "<p>Parent</p>",
                )
            )
        val child =
            commentRepository.save(
                Comment(
                    post = testPost,
                    author = testUser,
                    parentComment = parent,
                    markdownSource = "Reply",
                    renderedHtml = "<p>Reply</p>",
                )
            )
        val found = commentRepository.findById(child.id).orElse(null)

        assertNotNull(found)
        assertNotNull(found.parentComment)
        assertEquals(parent.id, found.parentComment!!.id)
    }

    @Test
    fun `top-level comment has null parentComment`() {
        val comment =
            commentRepository.save(
                Comment(
                    post = testPost,
                    author = testUser,
                    markdownSource = "Top level",
                    renderedHtml = "<p>Top level</p>",
                )
            )
        val found = commentRepository.findById(comment.id).orElse(null)

        assertNotNull(found)
        assertNull(found.parentComment)
    }
}
