/* Joseph B. Ottinger (C)2026 */
package dev.streampack.taxonomy

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.model.NewTagEvent
import dev.streampack.taxonomy.model.TagHintKind
import dev.streampack.taxonomy.model.TagReviewStatus
import dev.streampack.taxonomy.repository.TagAliasRepository
import dev.streampack.taxonomy.repository.TagRepository
import dev.streampack.taxonomy.repository.TagReviewRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.boot.test.context.SpringBootTest

/**
 * The AI near-miss (#140): after the write, off the writer's thread, on the moderation model; its
 * answer stored on the review entry; applied only when a threshold is set and met; nothing at all
 * without AI.
 */
@SpringBootTest
@ResetDatabaseBeforeEach
class TagNearMissTests {
    @Autowired lateinit var vocabulary: TagVocabulary
    @Autowired lateinit var curation: TagCuration
    @Autowired lateinit var tags: TagRepository
    @Autowired lateinit var aliases: TagAliasRepository
    @Autowired lateinit var reviews: TagReviewRepository

    private val sameThread = Executor { it.run() }

    @BeforeEach
    fun setUp() {
        listOf("compilers", "kubernetes", "java").forEach {
            tags.save(Tag(name = it, slug = vocabulary.uniqueSlug(it)))
        }
    }

    private fun nearMiss(
        ai: FakeAiService?,
        properties: TagProperties = TagProperties(),
        aiEnabled: Boolean = true,
    ): TagNearMiss =
        TagNearMiss(
            provider(ai),
            provider(AiProperties(enabled = aiEnabled, moderationModel = "test-haiku")),
            provider(properties),
            vocabulary,
            curation,
        )

    @Test
    fun `it asks the moderation model off the writer's thread, and never holds the writer`() {
        val gate = CountDownLatch(1)
        val ai = FakeAiService(TagNearMiss.Answer("kubernetes", 0.9, "k8s is kubernetes"), gate)
        val listener = nearMiss(ai)
        vocabulary.accept("k8s", "post")

        listener.onNewTag(NewTagEvent("k8s", "post")) // returns while the model is still "thinking"
        assertTrue(ai.calls.isEmpty() || ai.calls.single().thread != Thread.currentThread())
        gate.countDown()

        val call = ai.awaitCall()
        assertTrue(call.moderation, "the moderation model is used")
        assertNotEquals(Thread.currentThread(), call.thread)
        assertTrue(call.user.contains("New tag: k8s"))
        assertTrue(call.user.contains("kubernetes"))
        awaitUntil { reviews.findByTag("k8s")?.aiCandidate != null }
        val entry = reviews.findByTag("k8s")!!
        assertEquals(TagHintKind.AI, entry.hintKind)
        assertEquals("kubernetes", entry.aiCandidate)
        assertEquals(0.9, entry.aiConfidence)
        assertEquals("k8s is kubernetes", entry.aiReason)
        assertEquals("test-haiku", entry.aiModel)
    }

    @Test
    fun `its answer ranks and explains a rule's entry`() {
        vocabulary.accept("compiler", "factoid")
        val ai = FakeAiService(TagNearMiss.Answer("compilers", 0.97, "plural of compilers"))
        nearMiss(ai).classify(ai, NewTagEvent("compiler", "factoid"))
        val entry = reviews.findByTag("compiler")!!
        assertEquals(TagHintKind.PLURAL, entry.hintKind)
        assertEquals("compilers", entry.aiCandidate)
        assertEquals(TagReviewStatus.OPEN, entry.status)
    }

    @Test
    fun `auto-apply is off by default, however confident the model`() {
        vocabulary.accept("compiler", "post")
        val ai = FakeAiService(TagNearMiss.Answer("compilers", 1.0, "plural"))
        nearMiss(ai).apply { executor = sameThread }.onNewTag(NewTagEvent("compiler", "post"))
        assertEquals(TagReviewStatus.OPEN, reviews.findByTag("compiler")!!.status)
        assertFalse(aliases.existsById("compiler"))
    }

    @Test
    fun `with a threshold set, a confident answer is applied as an alias`() {
        vocabulary.accept("compiler", "post")
        val ai = FakeAiService(TagNearMiss.Answer("compilers", 0.95, "plural"))
        nearMiss(ai, TagProperties(aiAutoApplyThreshold = 0.9))
            .apply { executor = sameThread }
            .onNewTag(NewTagEvent("compiler", "post"))
        val entry = reviews.findByTag("compiler")!!
        assertEquals(TagReviewStatus.ALIASED, entry.status)
        assertEquals("ai:test-haiku", entry.actedBy)
        assertEquals("compilers", aliases.findById("compiler").get().tag.name)
        assertNull(tags.findByName("compiler"))
    }

    @Test
    fun `with a threshold set, a less confident answer is only recorded`() {
        vocabulary.accept("compiler", "post")
        val ai = FakeAiService(TagNearMiss.Answer("compilers", 0.5, "maybe"))
        nearMiss(ai, TagProperties(aiAutoApplyThreshold = 0.9))
            .apply { executor = sameThread }
            .onNewTag(NewTagEvent("compiler", "post"))
        assertEquals(TagReviewStatus.OPEN, reviews.findByTag("compiler")!!.status)
        assertFalse(aliases.existsById("compiler"))
    }

    @Test
    fun `a candidate that isn't an existing tag is not recorded as one`() {
        vocabulary.accept("quarkus", "post")
        val ai = FakeAiService(TagNearMiss.Answer("quark", 0.9, "invented"))
        nearMiss(ai).classify(ai, NewTagEvent("quarkus", "post"))
        assertNull(reviews.findByTag("quarkus"))
    }

    @Test
    fun `nothing runs when AI is disabled`() {
        val ai = FakeAiService(TagNearMiss.Answer("compilers", 1.0, "plural"))
        nearMiss(ai, aiEnabled = false)
            .apply { executor = sameThread }
            .onNewTag(NewTagEvent("compiler", "post"))
        nearMiss(null).apply { executor = sameThread }.onNewTag(NewTagEvent("compiler", "post"))
        assertTrue(ai.calls.isEmpty())
    }

    @Test
    fun `nothing runs when the near-miss is turned off`() {
        val ai = FakeAiService(TagNearMiss.Answer("compilers", 1.0, "plural"))
        nearMiss(ai, TagProperties(aiNearMiss = false))
            .apply { executor = sameThread }
            .onNewTag(NewTagEvent("compiler", "post"))
        assertTrue(ai.calls.isEmpty())
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out" }
            Thread.onSpinWait()
        }
    }

    companion object {
        inline fun <reified T : Any> provider(bean: T?): ObjectProvider<T> =
            StaticListableBeanFactory()
                .apply { if (bean != null) addBean("bean", bean) }
                .getBeanProvider(T::class.java)
    }

    /** One call to the fake model. */
    data class Call(
        val moderation: Boolean,
        val system: String,
        val user: String,
        val thread: Thread,
    )

    /**
     * An [AiService] that answers [answer] and records each call; its [moderation] view records
     * calls as made on the moderation model. With a [gate], each call waits for it.
     */
    class FakeAiService(
        private val answer: TagNearMiss.Answer?,
        private val gate: CountDownLatch? = null,
        private val isModeration: Boolean = false,
        val calls: MutableList<Call> = CopyOnWriteArrayList(),
    ) : AiService(NoChatModel(), AiProperties(enabled = true)) {
        override fun moderation(): AiService = FakeAiService(answer, gate, true, calls)

        override fun <T : Any> promptForObject(
            systemInstruction: String,
            userPrompt: String,
            responseType: Class<T>,
        ): T? {
            gate?.await(10, TimeUnit.SECONDS)
            calls += Call(isModeration, systemInstruction, userPrompt, Thread.currentThread())
            return responseType.cast(answer)
        }

        fun awaitCall(): Call {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (calls.isEmpty()) {
                check(System.nanoTime() < deadline) { "no call to the model" }
                Thread.onSpinWait()
            }
            return calls.first()
        }
    }

    class NoChatModel : ChatModel {
        override fun call(prompt: Prompt): ChatResponse = throw UnsupportedOperationException()
    }
}
