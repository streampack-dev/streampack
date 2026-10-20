/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.bootstrap

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.core.entity.User
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional

/** Posts stored before #270 get their headings' ids, rendered again from their markdown. */
@SpringBootTest
@Transactional
class HeadingIdBackfillTests {
    @Autowired lateinit var backfill: HeadingIdBackfill
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository

    private val then = Instant.now().minus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS)

    private fun post(author: User, title: String, markdown: String, html: String) =
        postRepository.save(
            Post(
                title = title,
                markdownSource = markdown,
                renderedHtml = html,
                status = PostStatus.APPROVED,
                publishedAt = then,
                author = author,
                createdAt = then,
                updatedAt = then,
            ),
        )

    @Test
    fun `an old post's headings get ids, without its updatedAt moving, and others are left alone`() {
        val author =
            userRepository.save(
                User(
                    username = "heading-ids",
                    email = "heading-ids@example.com",
                    emailVerified = true,
                    displayName = "Heading Ids",
                ),
            )
        val old =
            post(
                author,
                "Old",
                "## Why it matters\n\nText.",
                "<h2>Why it matters</h2>\n<p>Text.</p>",
            )
        val plain = post(author, "Plain", "Just text.", "<p>Just text.</p>")
        val done =
            post(
                author,
                "Done",
                "## Done\n\nText.",
                "<h2 id=\"section-done\">Done</h2>\n<p>Text.</p>",
            )

        backfill.run(DefaultApplicationArguments())

        val renewed = postRepository.findById(old.id).get()
        assertThat(renewed.renderedHtml)
            .contains("<h2 id=\"section-why-it-matters\">Why it matters</h2>")
        assertThat(renewed.updatedAt).isEqualTo(then)
        assertThat(postRepository.findById(plain.id).get().renderedHtml)
            .isEqualTo("<p>Just text.</p>")
        assertThat(postRepository.findById(done.id).get().renderedHtml)
            .isEqualTo("<h2 id=\"section-done\">Done</h2>\n<p>Text.</p>")

        // Run again, nothing more changes.
        backfill.run(DefaultApplicationArguments())
        assertThat(postRepository.findById(old.id).get().renderedHtml)
            .isEqualTo(renewed.renderedHtml)
    }
}
