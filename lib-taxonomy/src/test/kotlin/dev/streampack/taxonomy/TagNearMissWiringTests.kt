/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import dev.streampack.ai.service.AiService
import dev.streampack.taxonomy.TagNearMissTests.FakeAiService
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate

/**
 * The near-miss as wired (#140): a write that creates a tag publishes the event, and the model is
 * asked only once that write commits.
 */
@SpringBootTest(properties = ["streampack.ai.enabled=true"])
@ResetDatabaseBeforeEach
@Import(TagNearMissWiringTests.FakeAi::class)
class TagNearMissWiringTests {
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var reviews: TagReviewRepository
    @Autowired lateinit var transactions: TransactionTemplate
    @Autowired lateinit var ai: AiService

    @TestConfiguration
    class FakeAi {
        @Bean
        fun aiService(): AiService =
            FakeAiService(TagNearMiss.Answer("kubernetes", 0.8, "k8s abbreviates kubernetes"))
    }

    private val fake
        get() = ai as FakeAiService

    @Test
    fun `a committed new tag is classified on the moderation model`() {
        fake.calls.clear()
        tags.save(Tag(name = "kubernetes", slug = "kubernetes"))
        transactions.executeWithoutResult { vocabulary.accept("k8s", "post") }

        val call = fake.awaitCall()
        assertTrue(call.moderation)
        assertTrue(call.user.startsWith("New tag: k8s"))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (reviews.findByTag("k8s") == null && System.nanoTime() < deadline) Thread.onSpinWait()
        assertEquals("kubernetes", reviews.findByTag("k8s")?.aiCandidate)
    }

    @Test
    fun `a write that rolls back asks nothing`() {
        fake.calls.clear()
        tags.save(Tag(name = "kubernetes", slug = "kubernetes"))
        transactions.executeWithoutResult {
            vocabulary.accept("k8s", "post")
            it.setRollbackOnly()
        }
        Thread.sleep(300)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun `an existing tag asks nothing`() {
        fake.calls.clear()
        tags.save(Tag(name = "kubernetes", slug = "kubernetes"))
        transactions.executeWithoutResult { vocabulary.accept("Kubernetes", "post") }
        Thread.sleep(300)
        assertTrue(fake.calls.isEmpty())
    }
}
