/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.service

import dev.streampack.blog.entity.Post
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.transaction.annotation.Transactional

@SpringBootTest(properties = ["streampack.tick.scheduler.enabled=false"])
@Transactional
class FactoidUpdatedPostRerenderTickListenerTests {
    @TestConfiguration
    class ResolverConfig {
        @Bean
        @Primary
        fun testResolver(): MutableFactoidWikiLinkResolver = MutableFactoidWikiLinkResolver()

        @Bean
        @Primary
        fun markdownRenderingService(
            excerptSummarizerService: ExcerptSummarizerService,
            testResolver: MutableFactoidWikiLinkResolver,
        ): MarkdownRenderingService =
            MarkdownRenderingService(excerptSummarizerService, testResolver)
    }

    class MutableFactoidWikiLinkResolver : FactoidWikiLinkResolver {
        private val titles = ConcurrentHashMap<String, String>()

        fun put(selector: String, title: String) {
            titles[selector] = title
        }

        override fun resolve(selector: String): FactoidWikiLinkMetadata? {
            val title = titles[selector] ?: return null
            return FactoidWikiLinkMetadata(href = "https://example.com/$selector", title = title)
        }
    }

    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var factoidUpdateBuffer: FactoidUpdateBuffer
    @Autowired lateinit var listener: FactoidUpdatedPostRerenderTickListener
    @Autowired lateinit var markdownRenderingService: MarkdownRenderingService
    @Autowired lateinit var resolver: MutableFactoidWikiLinkResolver
    @Autowired lateinit var entityManager: EntityManager

    @BeforeEach
    fun setUp() {
        factoidUpdateBuffer.drain()
        val field = FactoidUpdatedPostRerenderTickListener::class.java.getDeclaredField("lastRunAt")
        field.isAccessible = true
        field.set(listener, null)
    }

    @Test
    fun `tick rerenders queued posts that reference updated factoids`() {
        resolver.put("thing", "old factoid text")
        val originalHtml = markdownRenderingService.render("See [[thing]] now.")
        val post =
            postRepository.save(
                Post(
                    title = "Factoid link post",
                    markdownSource = "See [[thing]] now.",
                    renderedHtml = originalHtml,
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now().minusSeconds(60),
                )
            )

        resolver.put("thing", "updated factoid text")
        val expectedHtml = markdownRenderingService.render("See [[thing]] now.")
        assertTrue(
            expectedHtml.contains("title=\"updated factoid text\""),
            "Expected rerendered HTML to contain updated factoid title, got: $expectedHtml",
        )
        factoidUpdateBuffer.record("thing")

        listener.onTick(
            Instant.now().plus(FactoidUpdatedPostRerenderTickListener.RERENDER_INTERVAL)
        )

        entityManager.flush()
        entityManager.clear()
        val refreshed = postRepository.findById(post.id).orElseThrow()
        assertEquals(expectedHtml, refreshed.renderedHtml)
    }

    @Test
    fun `tick rerenders queued posts that reference updated factoids via labeled wikilinks`() {
        resolver.put("thing", "old factoid text")
        val originalHtml = markdownRenderingService.render("See [[Thing label|thing]] now.")
        val post =
            postRepository.save(
                Post(
                    title = "Labeled factoid link post",
                    markdownSource = "See [[Thing label|thing]] now.",
                    renderedHtml = originalHtml,
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now().minusSeconds(60),
                )
            )

        resolver.put("thing", "updated factoid text")
        val expectedHtml = markdownRenderingService.render("See [[Thing label|thing]] now.")
        assertTrue(
            expectedHtml.contains("title=\"updated factoid text\""),
            "Expected rerendered HTML to contain updated factoid title, got: $expectedHtml",
        )
        factoidUpdateBuffer.record("thing")

        listener.onTick(
            Instant.now().plus(FactoidUpdatedPostRerenderTickListener.RERENDER_INTERVAL)
        )

        entityManager.flush()
        entityManager.clear()
        val refreshed = postRepository.findById(post.id).orElseThrow()
        assertEquals(expectedHtml, refreshed.renderedHtml)
    }
}
