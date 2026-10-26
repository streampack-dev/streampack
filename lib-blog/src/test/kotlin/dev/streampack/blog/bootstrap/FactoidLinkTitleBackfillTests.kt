/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.bootstrap

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.service.ExcerptSummarizerService
import dev.streampack.blog.service.FactoidWikiLinkMetadata
import dev.streampack.blog.service.FactoidWikiLinkResolver
import dev.streampack.blog.service.MarkdownRenderingService
import dev.streampack.core.entity.User
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional

/** Posts stored before #285 get their factoid links' titles, rendered again from their markdown. */
@SpringBootTest
@Transactional
class FactoidLinkTitleBackfillTests {
    @TestConfiguration
    class ResolverConfig {
        @Bean
        @Primary
        fun markdownRenderingService(
            excerptSummarizerService: ExcerptSummarizerService,
        ): MarkdownRenderingService =
            MarkdownRenderingService(
                excerptSummarizerService,
                object : FactoidWikiLinkResolver {
                    override fun resolve(selector: String): FactoidWikiLinkMetadata? =
                        if (selector == "bus factor") {
                            FactoidWikiLinkMetadata(title = "a measure of project risk")
                        } else {
                            null
                        }
                },
            )
    }

    @Autowired lateinit var backfill: FactoidLinkTitleBackfill
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
    fun `an old post's factoid links get titles, without its updatedAt moving`() {
        val author =
            userRepository.save(
                User(
                    username = "factoid-titles",
                    email = "factoid-titles@example.com",
                    emailVerified = true,
                    displayName = "Factoid Titles",
                ),
            )
        val old =
            post(
                author,
                "Old",
                "The [[bus factor]] is real.",
                "<p>The <a href=\"/factoids/bus%20factor\">bus factor</a> is real.</p>",
            )
        val unknown =
            post(
                author,
                "Unknown",
                "An [[unknown]] factoid.",
                "<p>An <a href=\"/factoids/unknown\">unknown</a> factoid.</p>",
            )

        backfill.run(DefaultApplicationArguments())

        val renewed = postRepository.findById(old.id).get()
        assertThat(renewed.renderedHtml)
            .contains(
                "<a href=\"/factoids/bus%20factor\" title=\"a measure of project risk\">bus factor</a>",
            )
        assertThat(renewed.updatedAt).isEqualTo(then)
        assertThat(postRepository.findById(unknown.id).get().renderedHtml)
            .isEqualTo("<p>An <a href=\"/factoids/unknown\">unknown</a> factoid.</p>")

        // Run again, nothing more changes.
        backfill.run(DefaultApplicationArguments())
        assertThat(postRepository.findById(old.id).get().renderedHtml)
            .isEqualTo(renewed.renderedHtml)
    }
}
