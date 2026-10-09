/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.service

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.ai.service.AiStructuredResponse
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssEntryText
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.entity.RssTextSource
import dev.streampack.rss.model.RssRating
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssEntryTextRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssItemGuessRepository
import dev.streampack.rss.service.RssRatingGuessService.GuessAnswer
import dev.streampack.rss.service.RssRatingGuessService.ItemGuess
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * The model's hidden guess (#187), turned on, with the model and the article pages faked: one call
 * per chunk to the moderation model, the prompt's examples and texts, and what's stored or skipped.
 * The passes are run directly, so nothing waits on the daily schedule.
 */
@SpringBootTest(
    properties =
        [
            "streampack.rss.rating.model-guess=true",
            "streampack.rss.rating.chunk-size=2",
            "streampack.rss.rating.min-rated-examples=2",
            // The scheduled pass never runs during these tests; they run passes themselves
            "streampack.rss.rating.first-guess-delay=P30D",
        ]
)
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class RssRatingGuessServiceTests {

    /** One call to the model: the model it went to, and the prompts. */
    data class Call(val model: String, val system: String, val prompt: String)

    /** Records calls; [answer] makes the answer from the user prompt. */
    class RecordingAi :
        AiService(Mockito.mock(ChatModel::class.java), AiProperties(enabled = true)) {
        val calls = CopyOnWriteArrayList<Call>()
        @Volatile var answer: (String) -> GuessAnswer? = ::allRates

        override fun moderation(): AiService = OnModel("moderation")

        override fun prompt(systemInstruction: String, userPrompt: String): String? {
            calls += Call("default", systemInstruction, userPrompt)
            return null
        }

        override fun <T : Any> promptForObjectWithRaw(
            systemInstruction: String,
            userPrompt: String,
            responseType: Class<T>,
        ): AiStructuredResponse<T> {
            calls += Call("default", systemInstruction, userPrompt)
            return AiStructuredResponse(null, null)
        }

        inner class OnModel(private val model: String) :
            AiService(Mockito.mock(ChatModel::class.java), AiProperties(enabled = true)) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : Any> promptForObjectWithRaw(
                systemInstruction: String,
                userPrompt: String,
                responseType: Class<T>,
            ): AiStructuredResponse<T> {
                calls += Call(model, systemInstruction, userPrompt)
                val value = answer(userPrompt)
                return AiStructuredResponse(value as T?, value?.toString())
            }
        }

        companion object {
            fun idsIn(prompt: String): List<String> =
                Regex("itemId: (\\S+)").findAll(prompt).map { it.groupValues[1] }.toList()

            fun allRates(prompt: String) =
                GuessAnswer(idsIn(prompt).map { ItemGuess(it, "RATES", 0.9, "Delightful.") })
        }
    }

    /** Article pages by address; records every read. */
    class FakePages : RssItemPageReader {
        val pages = ConcurrentHashMap<String, String>()
        val reads = CopyOnWriteArrayList<String>()

        override fun read(url: String): String? {
            reads += url
            return pages[url]
        }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun recordingAi() = RecordingAi()

        @Bean @Primary fun fakePages() = FakePages()
    }

    @Autowired lateinit var ai: RecordingAi
    @Autowired lateinit var pages: FakePages
    @Autowired lateinit var service: RssRatingGuessService
    @Autowired lateinit var ratings: RssRatingService
    @Autowired lateinit var feeds: RssFeedRepository
    @Autowired lateinit var entries: RssEntryRepository
    @Autowired lateinit var texts: RssEntryTextRepository
    @Autowired lateinit var guesses: RssItemGuessRepository
    @Autowired lateinit var aiProperties: AiProperties
    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService

    private lateinit var feed: RssFeed

    @BeforeEach
    fun setUp() {
        ai.calls.clear()
        ai.answer = RecordingAi::allRates
        pages.pages.clear()
        pages.reads.clear()
        feed = feeds.save(RssFeed(feedUrl = "https://a.example/feed.xml", title = "Feed A"))
    }

    private fun item(
        n: Int,
        summary: String? = "About item $n",
        receivedAt: Instant = Instant.now(),
    ): RssEntry =
        entries.save(
            RssEntry(
                feed = feed,
                guid = "https://a.example/$n",
                link = "https://a.example/$n",
                title = "Item $n",
                summary = summary,
                createdAt = receivedAt,
            )
        )

    private fun content(entry: RssEntry, text: String) =
        texts.save(RssEntryText(entry.id, RssTextSource.CONTENT, text, Instant.now()))

    @Test
    fun `one call per chunk, on the moderation model, and the answers stored`() {
        val items = (1..3).map { item(it) }

        val result = service.run()

        assertEquals(3, result.candidates)
        assertEquals(2, result.calls)
        assertEquals(3, result.stored)
        assertEquals(0, result.skipped)
        assertEquals(listOf("moderation", "moderation"), ai.calls.map { it.model })
        assertEquals(2, RecordingAi.idsIn(ai.calls[0].prompt).size)
        assertEquals(1, RecordingAi.idsIn(ai.calls[1].prompt).size)
        // The rubric and examples are the same for every chunk, so they can be cached
        assertEquals(ai.calls[0].system, ai.calls[1].system)
        assertTrue(ai.calls[0].system.startsWith(RssRatingGuessService.RUBRIC))
        val stored = guesses.findAll().associateBy { it.itemId }
        assertEquals(items.map { it.id }.toSet(), stored.keys)
        val one = stored.getValue(items[0].id)
        assertEquals(RssRating.RATES, one.label)
        assertEquals(0.9, one.confidence)
        assertEquals("Delightful.", one.reason)
        assertEquals(aiProperties.moderationModel, one.model)
    }

    @Test
    fun `already-guessed items are not sent again`() {
        val first = item(1)
        service.run()
        ai.calls.clear()
        val second = item(2)

        val result = service.run()

        assertEquals(1, result.candidates)
        assertEquals(listOf(second.id.toString()), RecordingAi.idsIn(ai.calls.single().prompt))
        assertFalse(first.id.toString() in ai.calls.single().prompt)
        ai.calls.clear()
        assertEquals(0, service.run().candidates)
        assertTrue(ai.calls.isEmpty())
    }

    @Test
    fun `items received before the lookback are not guessed`() {
        item(1, receivedAt = Instant.now().minus(Duration.ofDays(3)))
        val fresh = item(2)
        service.run()
        assertEquals(listOf(fresh.id.toString()), RecordingAi.idsIn(ai.calls.single().prompt))
    }

    @Test
    fun `with no ratings the seed examples are used`() {
        item(1)
        service.run()
        val system = ai.calls.single().system
        assertTrue("Pong Wars" in system, system)
        assertTrue("Total Annihilation" in system, system)
    }

    @Test
    fun `the examples are drawn from the editor's ratings, and not guessed themselves`() {
        val liked = (1..2).map { item(it, summary = "Liked summary $it") }
        val disliked = item(3, summary = "Disliked summary")
        liked.forEach { ratings.rate(it.id, RssRating.RATES, "editor") }
        ratings.rate(disliked.id, RssRating.DULL, "editor")
        val fresh = item(4)

        service.run()

        val call = ai.calls.single()
        assertTrue("- RATES: \"Item 2\" (Feed A). Liked summary 2" in call.system, call.system)
        assertTrue("- RATES: \"Item 1\" (Feed A). Liked summary 1" in call.system, call.system)
        assertTrue("- DULL: \"Item 3\" (Feed A). Disliked summary" in call.system, call.system)
        // Enough RATES of the editor's own: no seeds
        assertFalse("Pong Wars" in call.system, call.system)
        assertEquals(listOf(fresh.id.toString()), RecordingAi.idsIn(call.prompt))
    }

    @Test
    fun `a bad or partial answer is skipped, and tried again next time`() {
        val items = (1..2).map { item(it) }
        val unknown = UUID.randomUUID().toString()
        ai.answer = {
            GuessAnswer(
                listOf(
                    ItemGuess(items[0].id.toString(), "dull", 0.4, "Release notes."),
                    ItemGuess(items[1].id.toString(), "FASCINATING", 0.9, "Bad label."),
                    ItemGuess(items[1].id.toString(), "RATES", 1.5, "Bad confidence."),
                    ItemGuess(unknown, "RATES", 0.9, "Invented."),
                )
            )
        }

        val result = service.run()

        assertEquals(1, result.stored)
        assertEquals(1, result.skipped)
        val stored = guesses.findAll().single()
        assertEquals(items[0].id, stored.itemId)
        assertEquals(RssRating.DULL, stored.label)

        ai.answer = { null }
        ai.calls.clear()
        val again = service.run()
        assertEquals(1, again.candidates)
        assertEquals(0, again.stored)
        assertEquals(listOf(items[1].id.toString()), RecordingAi.idsIn(ai.calls.single().prompt))
    }

    @Test
    fun `a model that throws is logged and skipped`() {
        item(1)
        ai.answer = { throw IllegalStateException("boom") }
        val result = service.run()
        assertEquals(1, result.calls)
        assertEquals(1, result.skipped)
        assertTrue(guesses.findAll().isEmpty())
    }

    @Test
    fun `the text is the feed's content, else the page, else the summary`() {
        val withContent = item(1)
        content(withContent, "The whole story of item one, as the feed gave it.")
        val withPage = item(2)
        pages.pages[withPage.link] = "The article page of item two."
        val withSummary = item(3, summary = "Only a summary of three.")
        val bare = item(4, summary = null)

        service.run()

        val prompts = ai.calls.joinToString("\n") { it.prompt }
        fun block(entry: RssEntry) =
            prompts.substringAfter("itemId: ${entry.id}").substringBefore("itemId:")
        assertTrue(
            "source: content\ntext: The whole story of item one" in block(withContent),
            prompts,
        )
        assertTrue("link: https://a.example/1" in block(withContent), prompts)
        assertTrue("source: page\ntext: The article page of item two." in block(withPage), prompts)
        assertTrue("source: summary\ntext: Only a summary of three." in block(withSummary), prompts)
        assertTrue("source: title" in block(bare) && "text:" !in block(bare), prompts)
        // Pages are read only for the items with no content of their own, once each
        assertEquals(listOf(withPage.link, withSummary.link, bare.link), pages.reads.sorted())
        val sources = guesses.findAll().associate { it.itemId to it.textSource }
        assertEquals(RssTextSource.CONTENT, sources[withContent.id])
        assertEquals(RssTextSource.PAGE, sources[withPage.id])
        assertEquals(RssTextSource.SUMMARY, sources[withSummary.id])
        assertEquals(RssTextSource.TITLE, sources[bare.id])
        // The page's text is kept, and a page with none is remembered, so neither is read again
        assertEquals("The article page of item two.", texts.findById(withPage.id).get().content)
        assertEquals(RssTextSource.NONE, texts.findById(withSummary.id).get().source)
        assertNull(texts.findById(withSummary.id).get().content)
    }

    @Test
    fun `a page is fetched once, and never again`() {
        val entry = item(1)
        ai.answer = { null }
        service.run()
        service.run()
        assertEquals(2, ai.calls.size)
        assertEquals(listOf(entry.link), pages.reads)
    }

    @Test
    fun `the text is cut cleanly to about 4,000 characters`() {
        val entry = item(1)
        content(entry, (1..2000).joinToString(" ") { "word$it" })
        service.run()
        val text = ai.calls.single().prompt.substringAfter("text: ")
        assertTrue(text.length <= RssItemTextService.PROMPT_LENGTH, "${text.length}")
        assertTrue(text.length > RssItemTextService.PROMPT_LENGTH - 20, "${text.length}")
        assertTrue(text.endsWith("..."), text.takeLast(20))
        assertTrue(Regex("word\\d+\\.\\.\\.$").containsMatchIn(text), text.takeLast(20))
    }

    @Test
    fun `the manual run is for admins, and runs a pass`() {
        item(1)
        mockMvc.post("/admin/rss/rating-guesses/run").andExpect { status { isUnauthorized() } }
        fun token(name: String, role: Role) =
            jwtService.generateToken(
                userRepository
                    .save(
                        User(
                            username = name,
                            email = "$name@test.com",
                            displayName = name,
                            emailVerified = true,
                            role = role,
                        )
                    )
                    .toUserPrincipal()
            )
        mockMvc
            .post("/admin/rss/rating-guesses/run") {
                header("Authorization", "Bearer ${token("guessuser", Role.USER)}")
            }
            .andExpect { status { isForbidden() } }
        assertTrue(ai.calls.isEmpty())
        mockMvc
            .post("/admin/rss/rating-guesses/run") {
                header("Authorization", "Bearer ${token("guessadmin", Role.ADMIN)}")
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.candidates") { value(1) }
                jsonPath("$.calls") { value(1) }
                jsonPath("$.stored") { value(1) }
                jsonPath("$.skipped") { value(0) }
            }
        assertEquals(1, ai.calls.size)
    }
}
