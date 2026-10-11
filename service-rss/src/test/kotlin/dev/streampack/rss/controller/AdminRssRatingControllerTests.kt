/* Joseph B. Ottinger (C)2026 */
package dev.streampack.rss.controller

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.ai.service.AiStructuredResponse
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.core.service.JwtService
import dev.streampack.rss.entity.RssEntry
import dev.streampack.rss.entity.RssFeed
import dev.streampack.rss.entity.RssItemGuess
import dev.streampack.rss.entity.RssTextSource
import dev.streampack.rss.model.RssRating
import dev.streampack.rss.repository.RssEntryRepository
import dev.streampack.rss.repository.RssFeedRepository
import dev.streampack.rss.repository.RssItemGuessRepository
import dev.streampack.rss.service.RssRatingGuessTickListener
import dev.streampack.rss.service.RssRatingService
import dev.streampack.test.ResetDatabaseBeforeEach
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/**
 * Admins' ratings of feed items (#187): set, replaced and cleared with history; the admin list,
 * export and stats; admin-only throughout, and never on the public `/rss/items`. The guess is off
 * here, as by default.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
class AdminRssRatingControllerTests {
    /** Records any call to the model: none should be made while the guess is off. */
    class RecordingAi :
        AiService(Mockito.mock(ChatModel::class.java), AiProperties(enabled = true)) {
        val calls = CopyOnWriteArrayList<String>()

        override fun moderation(): AiService = this

        override fun prompt(systemInstruction: String, userPrompt: String): String? {
            calls += userPrompt
            return null
        }

        override fun <T : Any> promptForObjectWithRaw(
            systemInstruction: String,
            userPrompt: String,
            responseType: Class<T>,
        ): AiStructuredResponse<T> {
            calls += userPrompt
            return AiStructuredResponse(null, null)
        }
    }

    @TestConfiguration
    class Config {
        @Bean @Primary fun recordingAi() = RecordingAi()
    }

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var feeds: RssFeedRepository
    @Autowired lateinit var entries: RssEntryRepository
    @Autowired lateinit var guesses: RssItemGuessRepository
    @Autowired lateinit var ratings: RssRatingService
    @Autowired lateinit var tickListener: RssRatingGuessTickListener
    @Autowired lateinit var ai: RecordingAi

    private lateinit var adminToken: String
    private lateinit var userToken: String
    private lateinit var items: List<RssEntry>

    private fun token(name: String, role: Role): String =
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

    @BeforeEach
    fun setUp() {
        ai.calls.clear()
        adminToken = token("rateadmin", Role.ADMIN)
        userToken = token("rateuser", Role.USER)
        val feed =
            feeds.save(
                RssFeed(
                    feedUrl = "https://a.example/feed.xml",
                    siteUrl = "https://a.example",
                    title = "A",
                )
            )
        val base = Instant.parse("2026-10-01T00:00:00Z")
        items =
            (1..6).map { n ->
                entries.save(
                    RssEntry(
                        feed = feed,
                        guid = "https://a.example/$n",
                        link = "https://a.example/$n",
                        title = "Item $n",
                        summary = "About item $n",
                        publishedAt = base.plus(Duration.ofHours(n.toLong())),
                    )
                )
            }
    }

    private fun item(n: Int) = items[n - 1].id

    private fun rate(id: UUID, rating: String, token: String? = adminToken) =
        mockMvc.put("/admin/rss/items/$id/rating") {
            token?.let { header("Authorization", "Bearer $it") }
            contentType = MediaType.APPLICATION_JSON
            content = """{"rating":"$rating"}"""
        }

    private fun guess(
        n: Int,
        label: RssRating,
        confidence: Double = 0.8,
        guessedAt: Instant = Instant.parse("2026-10-02T00:00:00Z"),
        rubric: String? = "0123456789ab",
    ) =
        guesses.save(
            RssItemGuess(
                itemId = item(n),
                label = label,
                confidence = confidence,
                reason = "Because $n",
                model = "test-model",
                textSource = RssTextSource.CONTENT,
                guessedAt = guessedAt,
                rubric = rubric,
            )
        )

    @Test
    fun `every endpoint is for admins`() {
        val id = item(1)
        rate(id, "RATES", null).andExpect { status { isUnauthorized() } }
        rate(id, "RATES", userToken).andExpect { status { isForbidden() } }
        mockMvc.delete("/admin/rss/items/$id/rating").andExpect { status { isUnauthorized() } }
        mockMvc
            .delete("/admin/rss/items/$id/rating") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
        for (path in
            listOf(
                "/admin/rss/items",
                "/admin/rss/ratings.csv",
                "/admin/rss/rating-stats",
                "/admin/rss/rating-guesses",
            )) {
            mockMvc.get(path).andExpect { status { isUnauthorized() } }
            mockMvc
                .get(path) { header("Authorization", "Bearer $userToken") }
                .andExpect { status { isForbidden() } }
        }
        mockMvc.post("/admin/rss/rating-guesses/run").andExpect { status { isUnauthorized() } }
        mockMvc
            .post("/admin/rss/rating-guesses/run") { header("Authorization", "Bearer $userToken") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `an unknown item is a 404, a bad rating a 400`() {
        val unknown = UUID.randomUUID()
        rate(unknown, "RATES").andExpect { status { isNotFound() } }
        mockMvc
            .delete("/admin/rss/items/$unknown/rating") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { status { isNotFound() } }
        rate(item(1), "WONDERFUL").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a rating is set, replaced and cleared, and the history kept`() {
        val id = item(1)
        rate(id, "RATES").andExpect {
            status { isOk() }
            jsonPath("$.itemId") { value(id.toString()) }
            jsonPath("$.rating") { value("RATES") }
            jsonPath("$.ratedBy") { value("rateadmin") }
            jsonPath("$.ratedAt") { exists() }
        }
        rate(id, "DULL").andExpect {
            status { isOk() }
            jsonPath("$.rating") { value("DULL") }
        }
        // The same rating again changes nothing
        rate(id, "DULL").andExpect { status { isOk() } }
        mockMvc
            .delete("/admin/rss/items/$id/rating") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isNoContent() } }
        // Clearing an unrated item is fine, and adds nothing
        mockMvc
            .delete("/admin/rss/items/$id/rating") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isNoContent() } }

        val history = ratings.historyOf(id)
        assertEquals(
            listOf(
                null to RssRating.RATES,
                RssRating.RATES to RssRating.DULL,
                RssRating.DULL to null,
            ),
            history.map { it.previous to it.rating },
        )
        assertTrue(history.all { it.actedBy == "rateadmin" })
        mockMvc
            .get("/admin/rss/items?rating=unrated") {
                header("Authorization", "Bearer $adminToken")
            }
            .andExpect { jsonPath("$.totalCount") { value(6) } }
    }

    @Test
    fun `the admin list filters by rating, with totals`() {
        rate(item(1), "RATES")
        rate(item(2), "RATES")
        rate(item(3), "MIGHT")
        rate(item(4), "DULL")
        guess(1, RssRating.DULL)

        fun list(query: String) =
            mockMvc.get("/admin/rss/items?$query") { header("Authorization", "Bearer $adminToken") }

        list("rating=rates").andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(2) }
            jsonPath("$.totalPages") { value(1) }
            jsonPath("$.items.length()") { value(2) }
            // Newest first, as /rss/items lists them
            jsonPath("$.items[0].title") { value("Item 2") }
            jsonPath("$.items[0].rating") { value("RATES") }
            jsonPath("$.items[0].ratedBy") { value("rateadmin") }
            jsonPath("$.items[0].ratedAt") { exists() }
            jsonPath("$.items[0].feedTitle") { value("A") }
            jsonPath("$.items[0].summary") { value("About item 2") }
            jsonPath("$.items[0].categories") { isArray() }
            // The model's guess never shows where items are rated
            jsonPath("$.items[1].guess") { doesNotExist() }
            jsonPath("$.items[1].label") { doesNotExist() }
            jsonPath("$.items[1].confidence") { doesNotExist() }
        }
        list("rating=MIGHT").andExpect { jsonPath("$.totalCount") { value(1) } }
        list("rating=dull").andExpect { jsonPath("$.items[0].title") { value("Item 4") } }
        list("rating=unrated").andExpect {
            jsonPath("$.totalCount") { value(2) }
            jsonPath("$.items[0].rating") { value(null) }
        }
        list("rating=all&size=4&page=1").andExpect {
            jsonPath("$.totalCount") { value(6) }
            jsonPath("$.totalPages") { value(2) }
            jsonPath("$.page") { value(1) }
            jsonPath("$.items.length()") { value(2) }
        }
        list("title=item 3").andExpect { jsonPath("$.totalCount") { value(1) } }
        list("rating=bogus").andExpect { status { isBadRequest() } }
        list("size=101").andExpect { status { isBadRequest() } }
        list("page=-1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `ratings and guesses never appear on the public list`() {
        rate(item(1), "RATES")
        guess(1, RssRating.RATES)
        mockMvc.get("/rss/items").andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(6) }
            jsonPath("$.items[5].title") { value("Item 1") }
            jsonPath("$.items[5].rating") { doesNotExist() }
            jsonPath("$.items[5].ratedBy") { doesNotExist() }
            jsonPath("$.items[5].ratedAt") { doesNotExist() }
            jsonPath("$.items[5].label") { doesNotExist() }
            jsonPath("$.items[5].confidence") { doesNotExist() }
            jsonPath("$.items[5].content") { doesNotExist() }
        }
        val body = mockMvc.get("/rss/items").andReturn().response.contentAsString
        assertTrue("RATES" !in body && "rateadmin" !in body && "Because" !in body, body)
    }

    @Test
    fun `the CSV export holds every rated or guessed item, quoted safely`() {
        val odd =
            entries.save(
                RssEntry(
                    feed = feeds.findAll().single(),
                    guid = "https://a.example/odd",
                    link = "https://a.example/odd",
                    title = "=HYPERLINK(\"x\"), a \"quoted\" title",
                )
            )
        rate(item(1), "RATES")
        rate(odd.id, "MIGHT")
        guess(1, RssRating.DULL, 0.25)
        guess(2, RssRating.RATES)

        val response =
            mockMvc
                .get("/admin/rss/ratings.csv") { header("Authorization", "Bearer $adminToken") }
                .andExpect {
                    status { isOk() }
                    content { contentTypeCompatibleWith("text/csv") }
                    header {
                        string("Content-Disposition", "attachment; filename=\"rss-ratings.csv\"")
                    }
                }
                .andReturn()
                .response
        val lines = response.contentAsString.split("\r\n").filter { it.isNotEmpty() }
        assertEquals(
            "item_id,feed,title,link,published,rating,rated_by,rated_at,guess,guess_confidence," +
                "guess_reason,guess_model,guess_source,guessed_at,guess_rubric",
            lines[0],
        )
        assertEquals(4, lines.size, response.contentAsString)
        // Newest rating first: the odd one, then item 1 with its guess, then item 2's guess alone
        assertTrue(
            lines[1].startsWith("${odd.id},A,\"'=HYPERLINK(\"\"x\"\"), a \"\"quoted\"\" title\","),
            lines[1],
        )
        assertTrue(lines[1].contains(",MIGHT,rateadmin,"), lines[1])
        val one = lines[2]
        assertTrue(
            one.startsWith(
                "${item(1)},A,Item 1,https://a.example/1,2026-10-01T01:00:00Z,RATES,rateadmin,"
            ),
            one,
        )
        assertTrue(
            one.endsWith(
                ",DULL,0.25,Because 1,test-model,content,2026-10-02T00:00:00Z,0123456789ab"
            ),
            one,
        )
        assertTrue(
            lines[3].startsWith(
                "${item(2)},A,Item 2,https://a.example/2,2026-10-01T02:00:00Z,,,,RATES,0.8,"
            ),
            lines[3],
        )
    }

    @Test
    fun `an older guess has an empty rubric in the CSV`() {
        guess(1, RssRating.MIGHT, rubric = null)
        val body =
            mockMvc
                .get("/admin/rss/ratings.csv") { header("Authorization", "Bearer $adminToken") }
                .andReturn()
                .response
                .contentAsString
        val line = body.split("\r\n")[1]
        assertTrue(line.endsWith(",content,2026-10-02T00:00:00Z,"), line)
    }

    private fun listGuesses(query: String = "") =
        mockMvc.get("/admin/rss/rating-guesses?$query") {
            header("Authorization", "Bearer $adminToken")
        }

    /** Guesses on items 1 to 5, an hour apart (item 5 newest); items 1 to 3 rated. */
    private fun guessFixture() {
        val base = Instant.parse("2026-10-03T00:00:00Z")
        guess(1, RssRating.RATES, 0.9, base.plus(Duration.ofHours(1)))
        guess(2, RssRating.DULL, 0.6, base.plus(Duration.ofHours(2)))
        guess(3, RssRating.MIGHT, 0.5, base.plus(Duration.ofHours(3)), rubric = null)
        guess(4, RssRating.DULL, 0.7, base.plus(Duration.ofHours(4)))
        guess(5, RssRating.RATES, 0.8, base.plus(Duration.ofHours(5)))
        rate(item(1), "RATES")
        rate(item(2), "RATES")
        rate(item(3), "MIGHT")
    }

    @Test
    fun `the list of guesses, newest first, with the rating and whether it agrees`() {
        guessFixture()
        listGuesses().andExpect {
            status { isOk() }
            jsonPath("$.totalCount") { value(5) }
            jsonPath("$.totalPages") { value(1) }
            jsonPath("$.page") { value(0) }
            jsonPath("$.items.length()") { value(5) }
            jsonPath("$.items[0].itemId") { value(item(5).toString()) }
            jsonPath("$.items[0].title") { value("Item 5") }
            jsonPath("$.items[0].link") { value("https://a.example/5") }
            jsonPath("$.items[0].feedTitle") { value("A") }
            jsonPath("$.items[0].label") { value("RATES") }
            jsonPath("$.items[0].confidence") { value(0.8) }
            jsonPath("$.items[0].reason") { value("Because 5") }
            jsonPath("$.items[0].textSource") { value("CONTENT") }
            jsonPath("$.items[0].model") { value("test-model") }
            jsonPath("$.items[0].rubric") { value("0123456789ab") }
            jsonPath("$.items[0].guessedAt") { value("2026-10-03T05:00:00Z") }
            // Unrated: no rating, and nothing to agree with
            jsonPath("$.items[0].rating") { value(null) }
            jsonPath("$.items[0].agrees") { value(null) }
            jsonPath("$.items[2].title") { value("Item 3") }
            jsonPath("$.items[2].rubric") { value(null) }
            jsonPath("$.items[2].rating") { value("MIGHT") }
            jsonPath("$.items[2].agrees") { value(true) }
            jsonPath("$.items[3].title") { value("Item 2") }
            jsonPath("$.items[3].label") { value("DULL") }
            jsonPath("$.items[3].rating") { value("RATES") }
            jsonPath("$.items[3].agrees") { value(false) }
            jsonPath("$.items[4].title") { value("Item 1") }
            jsonPath("$.items[4].agrees") { value(true) }
        }
    }

    @Test
    fun `the list of guesses pages and filters`() {
        guessFixture()
        listGuesses("size=2&page=1").andExpect {
            jsonPath("$.totalCount") { value(5) }
            jsonPath("$.totalPages") { value(3) }
            jsonPath("$.page") { value(1) }
            jsonPath("$.items.length()") { value(2) }
            jsonPath("$.items[0].title") { value("Item 3") }
            jsonPath("$.items[1].title") { value("Item 2") }
        }
        listGuesses("label=rates").andExpect {
            jsonPath("$.totalCount") { value(2) }
            jsonPath("$.items[0].title") { value("Item 5") }
            jsonPath("$.items[1].title") { value("Item 1") }
        }
        listGuesses("label=DULL").andExpect { jsonPath("$.totalCount") { value(2) } }
        listGuesses("label=might").andExpect { jsonPath("$.totalCount") { value(1) } }
        listGuesses("rated=yes").andExpect {
            jsonPath("$.totalCount") { value(3) }
            jsonPath("$.items[0].title") { value("Item 3") }
        }
        listGuesses("rated=no").andExpect {
            jsonPath("$.totalCount") { value(2) }
            jsonPath("$.items[0].title") { value("Item 5") }
            jsonPath("$.items[1].title") { value("Item 4") }
        }
        listGuesses("label=dull&rated=yes").andExpect {
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.items[0].title") { value("Item 2") }
        }
        listGuesses("title=ITEM 4").andExpect {
            jsonPath("$.totalCount") { value(1) }
            jsonPath("$.items[0].title") { value("Item 4") }
        }
        listGuesses("feed=a").andExpect { jsonPath("$.totalCount") { value(5) } }
        listGuesses("feed=https://a.example/feed.xml").andExpect {
            jsonPath("$.totalCount") { value(5) }
        }
        listGuesses("feed=nowhere").andExpect {
            jsonPath("$.totalCount") { value(0) }
            jsonPath("$.items.length()") { value(0) }
        }
        listGuesses("label=bogus").andExpect { status { isBadRequest() } }
        listGuesses("rated=maybe").andExpect { status { isBadRequest() } }
        listGuesses("size=0").andExpect { status { isBadRequest() } }
        listGuesses("size=101").andExpect { status { isBadRequest() } }
        listGuesses("page=-1").andExpect { status { isBadRequest() } }
        listGuesses("size=100").andExpect { status { isOk() } }
    }

    @Test
    fun `the item list still never carries a guess`() {
        guessFixture()
        val body =
            mockMvc
                .get("/admin/rss/items") { header("Authorization", "Bearer $adminToken") }
                .andExpect {
                    status { isOk() }
                    jsonPath("$.items[0].title") { value("Item 6") }
                }
                .andReturn()
                .response
                .contentAsString
        for (field in listOf("label", "confidence", "reason", "textSource", "rubric", "agrees")) {
            assertTrue("\"$field\"" !in body, "$field in $body")
        }
        assertTrue("Because" !in body && "0123456789ab" !in body, body)
    }

    @Test
    fun `the stats on a known fixture`() {
        rate(item(1), "RATES")
        rate(item(2), "RATES")
        rate(item(3), "DULL")
        rate(item(4), "MIGHT")
        rate(item(5), "DULL")
        guess(1, RssRating.RATES)
        guess(2, RssRating.DULL)
        guess(3, RssRating.RATES)
        guess(4, RssRating.MIGHT)
        guess(6, RssRating.DULL)

        mockMvc
            .get("/admin/rss/rating-stats") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                status { isOk() }
                jsonPath("$.counts.rates") { value(2) }
                jsonPath("$.counts.might") { value(1) }
                jsonPath("$.counts.dull") { value(2) }
                jsonPath("$.counts.rated") { value(5) }
                jsonPath("$.counts.guessed") { value(5) }
                jsonPath("$.counts.compared") { value(4) }
                jsonPath("$.agreement") { value(0.5) }
                jsonPath("$.perClass[0].rating") { value("RATES") }
                jsonPath("$.perClass[0].rated") { value(2) }
                jsonPath("$.perClass[0].guessed") { value(2) }
                jsonPath("$.perClass[0].agreed") { value(1) }
                jsonPath("$.perClass[0].recall") { value(0.5) }
                jsonPath("$.perClass[0].precision") { value(0.5) }
                jsonPath("$.perClass[1].rating") { value("MIGHT") }
                jsonPath("$.perClass[1].recall") { value(1.0) }
                jsonPath("$.perClass[2].rating") { value("DULL") }
                jsonPath("$.perClass[2].rated") { value(1) }
                jsonPath("$.perClass[2].agreed") { value(0) }
                jsonPath("$.perClass[2].recall") { value(0.0) }
                jsonPath("$.confusion.length()") { value(9) }
                jsonPath("$.confusion[?(@.rated == 'RATES' && @.guessed == 'RATES')].count") {
                    value(1)
                }
                jsonPath("$.confusion[?(@.rated == 'RATES' && @.guessed == 'DULL')].count") {
                    value(1)
                }
                jsonPath("$.confusion[?(@.rated == 'DULL' && @.guessed == 'RATES')].count") {
                    value(1)
                }
                jsonPath("$.confusion[?(@.rated == 'MIGHT' && @.guessed == 'MIGHT')].count") {
                    value(1)
                }
                jsonPath("$.confusion[?(@.rated == 'DULL' && @.guessed == 'DULL')].count") {
                    value(0)
                }
                jsonPath("$.guessedRatesRatedDull.length()") { value(1) }
                jsonPath("$.guessedRatesRatedDull[0].itemId") { value(item(3).toString()) }
                jsonPath("$.guessedRatesRatedDull[0].title") { value("Item 3") }
                jsonPath("$.ratedRatesGuessedDull.length()") { value(1) }
                jsonPath("$.ratedRatesGuessedDull[0].itemId") { value(item(2).toString()) }
            }
    }

    @Test
    fun `with no guesses the stats have nothing to divide`() {
        rate(item(1), "RATES")
        mockMvc
            .get("/admin/rss/rating-stats") { header("Authorization", "Bearer $adminToken") }
            .andExpect {
                jsonPath("$.counts.compared") { value(0) }
                jsonPath("$.agreement") { value(null) }
                jsonPath("$.perClass[0].recall") { value(null) }
            }
    }

    @Test
    fun `the guess is off by default, so no pass and no call`() {
        mockMvc
            .post("/admin/rss/rating-guesses/run") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isConflict() } }
        // Long past when a pass would be due: the listener does nothing while the guess is off
        tickListener.onTick(Instant.now().plus(Duration.ofDays(3)))
        tickListener.onTick(Instant.now().plus(Duration.ofDays(6)))
        assertTrue(ai.calls.isEmpty())
        assertTrue(guesses.findAll().isEmpty())
    }
}
