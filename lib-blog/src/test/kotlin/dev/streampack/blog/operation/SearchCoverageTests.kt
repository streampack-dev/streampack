/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.ContentSummary
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.service.SearchQuery
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.taxonomy.entity.Tag
import dev.streampack.taxonomy.repository.TagRepository
import jakarta.persistence.EntityManager
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** Search covers title, excerpt, body and tags, ranked in that order, with web-style queries. */
@SpringBootTest
@Transactional
class SearchCoverageTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var entityManager: EntityManager
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository

    private lateinit var author: User

    @BeforeEach
    fun setUp() {
        author =
            userRepository.save(
                User(
                    username = "search-author",
                    email = "search@author.test",
                    displayName = "Search Author",
                    emailVerified = true,
                    role = Role.USER,
                ),
            )
    }

    private fun post(
        title: String,
        excerpt: String = "An unrelated summary",
        body: String = "Unrelated body text.",
        tags: List<String> = emptyList(),
        html: String = "<p>$body</p>",
    ): Post {
        val post =
            postRepository.save(
                Post(
                    title = title,
                    markdownSource = body,
                    renderedHtml = html,
                    excerpt = excerpt,
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now().minusSeconds(60),
                    author = author,
                ),
            )
        tags.forEach { name ->
            val tag =
                tagRepository.findByName(name) ?: tagRepository.save(Tag(name = name, slug = name))
            postTagRepository.save(PostTag(post = post, tag = tag))
        }
        entityManager.flush()
        return post
    }

    private fun search(query: String): List<String> = searchSummaries(query).map { it.title }

    private fun searchSummaries(query: String, size: Int = 20): List<ContentSummary> {
        val response =
            (searchResult(query, size) as OperationResult.Success).payload as ContentListResponse
        if (response.totalCount <= minOf(size, SearchQuery.MAX_PAGE_SIZE)) {
            assertEquals(
                response.posts.size.toLong(),
                response.totalCount,
                "count query agrees with results",
            )
        }
        return response.posts
    }

    private fun searchResult(query: String, size: Int = 20): OperationResult {
        val message =
            MessageBuilder.withPayload(FindContentRequest.Search(query, size = size))
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog-service",
                        replyTo = "posts",
                    ),
                )
                .build()
        return eventGateway.process(message) as OperationResult
    }

    /** The marked words of a result's passage. */
    private fun marked(summary: ContentSummary): List<String> =
        summary.match!!.let { m -> m.marks.map { m.text.substring(it.start, it.end) } }

    @Test
    fun `a term only in the body is found`() {
        post("Helidon 27 Released", body = "Helidon now builds on virtual threads throughout.")

        assertEquals(listOf("Helidon 27 Released"), search("virtual threads"))
    }

    @Test
    fun `a tag is found, and ranks above a mention in the body`() {
        post("Mentions it", body = "Somewhere in here we mention quarkus once.")
        post("Tagged with it", tags = listOf("quarkus"))

        assertEquals(listOf("Tagged with it", "Mentions it"), search("quarkus"))
    }

    @Test
    fun `title outranks excerpt, which outranks body`() {
        post("In the body", body = "A long discussion of graalvm native images.")
        post("GraalVM in the title")
        post("In the excerpt", excerpt = "Notes on graalvm startup.")

        assertEquals(
            listOf("GraalVM in the title", "In the excerpt", "In the body"),
            search("graalvm"),
        )
    }

    @Test
    fun `quoted phrases match the phrase, not the words apart`() {
        post("Adjacent", body = "We benchmarked virtual threads under load.")
        post("Apart", body = "The threads were real, the machines virtual.")

        assertEquals(listOf("Adjacent"), search("\"virtual threads\""))
    }

    @Test
    fun `a minus excludes a term`() {
        post("JVM and Kotlin", body = "The jvm runs kotlin well.")
        post("JVM alone", body = "The jvm runs everything.")

        assertEquals(listOf("JVM alone"), search("jvm -kotlin"))
    }

    @Test
    fun `retagging a post updates what finds it`() {
        val p = post("Retagged", tags = listOf("oldtag"))
        assertEquals(listOf("Retagged"), search("oldtag"))

        postTagRepository.deleteByPost(p.id)
        postTagRepository.save(
            PostTag(post = p, tag = tagRepository.save(Tag(name = "newtag", slug = "newtag"))),
        )
        entityManager.flush()
        entityManager.clear()

        assertEquals(emptyList<String>(), search("oldtag"))
        assertEquals(listOf("Retagged"), search("newtag"))
    }

    @Test
    fun `editing the body updates what finds it`() {
        val p = post("Edited", body = "Originally about ant.")
        postRepository.save(
            p.copy(markdownSource = "Now about gradle.", renderedHtml = "<p>Now about gradle.</p>"),
        )
        entityManager.flush()

        assertEquals(listOf("Edited"), search("gradle"))
    }

    @Test
    fun `recording an access doesn't rebuild the search vector`() {
        val p = post("Often read")
        // Plant a sentinel vector; only a rebuild would replace it.
        entityManager
            .createNativeQuery(
                "UPDATE posts SET search_vector = to_tsvector('simple', 'sentinel') WHERE id = :id",
            )
            .setParameter("id", p.id)
            .executeUpdate()
        entityManager.clear()

        // What RecordPostAccessOperation does: save the whole entity with a new access count.
        val loaded = postRepository.findById(p.id).orElseThrow()
        postRepository.save(
            loaded.copy(accessCount = loaded.accessCount + 1, lastAccessedAt = Instant.now()),
        )
        entityManager.flush()

        val vector =
            entityManager
                .createNativeQuery("SELECT search_vector::text FROM posts WHERE id = :id")
                .setParameter("id", p.id)
                .singleResult
        assertEquals("'sentinel':1", vector)
    }

    @Test
    fun `posts in hidden categories aren't found, and are again once moved out`() {
        val hidden = categoryRepository.save(Category(name = "_drafts-desk", slug = "drafts-desk"))
        post("Visible zeppelin")
        val tucked = post("Hidden zeppelin")
        val link = postCategoryRepository.save(PostCategory(post = tucked, category = hidden))
        entityManager.flush()

        assertEquals(listOf("Visible zeppelin"), search("zeppelin"))

        postCategoryRepository.delete(link)
        entityManager.flush()

        assertEquals(setOf("Visible zeppelin", "Hidden zeppelin"), search("zeppelin").toSet())
    }

    // #227: quoted words match exactly; unquoted words keep stemming.

    @Test
    fun `an unquoted word is stemmed, a quoted one is exact`() {
        post("Runtimes", body = "An emerging runtime could use the same modules.")
        post("Behaviour", body = "The behaviour is emergent, not designed.")

        assertEquals(setOf("Runtimes", "Behaviour"), search("emergent").toSet())
        assertEquals(listOf("Behaviour"), search("\"emergent\""))
        assertEquals(listOf("Behaviour"), search("\"Emergent\""))
    }

    @Test
    fun `a quoted phrase needs its words together, in order`() {
        post("Together", body = "Kotlin coroutines make this simple.")
        post("Apart", body = "Coroutines in Kotlin make this simple.")

        assertEquals(setOf("Together", "Apart"), search("kotlin coroutines").toSet())
        assertEquals(listOf("Together"), search("\"kotlin coroutines\""))
    }

    @Test
    fun `quoted and stemmed words mix, and or gives alternatives`() {
        post("Runtimes", body = "An emerging runtime, built on threads.")
        post("Behaviour", body = "The behaviour is emergent, across threads.")
        post("Neither", body = "Nothing emerges here but threads.")

        assertEquals(listOf("Behaviour"), search("thread \"emergent\""))
        assertEquals(
            setOf("Runtimes", "Behaviour"),
            search("\"emergent\" or \"emerging\"").toSet(),
        )
    }

    @Test
    fun `an unclosed quote is exact to the end of the query`() {
        post("Together", body = "Kotlin coroutines make this simple.")
        post("Apart", body = "Coroutines in Kotlin make this simple.")

        assertEquals(listOf("Together"), search("\"kotlin coroutines"))
    }

    @Test
    fun `an exact phrase doesn't run from one field into the next`() {
        post("Kotlin", excerpt = "Coroutines everywhere", body = "Nothing else.")

        assertEquals(listOf("Kotlin"), search("kotlin coroutines"))
        assertEquals(emptyList<String>(), search("\"kotlin coroutines\""))
    }

    @Test
    fun `common words find nothing alone, but count inside a quoted phrase`() {
        post("Art", body = "This is the state of the art in parsers.")
        post("Other", body = "The art of the state is another thing.")

        assertEquals(emptyList<String>(), search("the"))
        assertEquals(emptyList<String>(), search("\"the\""))
        assertEquals(listOf("Art"), search("\"state of the art\""))
    }

    // Limits, so a query can't be made expensive.

    @Test
    fun `a query over the length limit is refused`() {
        val result = searchResult("a".repeat(SearchQuery.MAX_LENGTH + 1))
        assertEquals(true, (result as OperationResult.Error).message.contains("too long"))
        assertEquals(
            true,
            searchResult("a".repeat(SearchQuery.MAX_LENGTH)) is OperationResult.Success,
        )
    }

    @Test
    fun `too many quoted phrases are refused`() {
        val many = (1..SearchQuery.MAX_PHRASES + 1).joinToString(" ") { "\"w$it\"" }
        val result = searchResult(many)
        assertEquals(true, (result as OperationResult.Error).message.contains("quoted"))
        val enough = (1..SearchQuery.MAX_PHRASES).joinToString(" ") { "\"w$it\"" }
        assertEquals(true, searchResult(enough) is OperationResult.Success)
    }

    @Test
    fun `a page holds at most the page limit`() {
        repeat(SearchQuery.MAX_PAGE_SIZE + 5) { post("Limit $it", body = "pagination limit") }

        assertEquals(SearchQuery.MAX_PAGE_SIZE, searchSummaries("pagination", size = 10_000).size)
    }

    // #226: each result says why it matched.

    @Test
    fun `a stemmed match marks the word that matched`() {
        post("Runtimes", body = "Some preamble. An emerging runtime could use the same modules.")

        val result = searchSummaries("emergent").single()
        assertEquals("body", result.match!!.source)
        assertEquals(listOf("emerging"), marked(result))
    }

    @Test
    fun `a quoted word marks only the exact word`() {
        post("Both", body = "An emerging runtime shows emergent behaviour.")

        val result = searchSummaries("\"emergent\"").single()
        assertEquals(listOf("emergent"), marked(result))
    }

    @Test
    fun `a match only in the title or a tag says so`() {
        post("Emergent Design")
        post("Tagged", tags = listOf("emergence"))

        val byTitle = searchSummaries("\"emergent\"").single()
        assertEquals("title", byTitle.match!!.source)
        assertEquals(listOf("Emergent"), marked(byTitle))

        val byTag = searchSummaries("emergence").single { it.title == "Tagged" }
        assertEquals("tags", byTag.match!!.source)
        assertEquals(listOf("emergence"), marked(byTag))
    }

    @Test
    fun `the passage is plain text, with no markup from the post`() {
        post(
            "Markup",
            body =
                "Tom &amp; Jerry use <a href=\"https://example.com/x\">emergent</a> <em>tricks</em>.",
        )

        for (query in listOf("emergent", "\"emergent\"")) {
            val result = searchSummaries(query).single()
            assertEquals(false, result.match!!.text.contains('<'), result.match!!.text)
            assertEquals(false, result.match!!.text.contains("&amp;"), result.match!!.text)
            assertEquals(listOf("emergent"), marked(result))
        }
        // The exact passage keeps the whole short sentence, so the decoded entity shows.
        assertEquals(
            "Tom & Jerry use emergent tricks.",
            searchSummaries("\"emergent\"").single().match!!.text,
        )
    }

    @Test
    fun `block tags keep words apart, inline tags don't split them`() {
        post(
            "Blocks",
            html =
                "<p>first</p><p>emergent</p><ul><li>second</li></ul><p><strong>un</strong>expected.</p>",
        )

        val text = searchSummaries("\"emergent\"").single().match!!.text
        assertEquals("first emergent second unexpected.", text)
    }

    @Test
    fun `a word split by inline markup is indexed whole, stemmed or exact`() {
        post("Split", html = "<p>Something <strong>un</strong>expected happened.</p>")

        assertEquals(listOf("Split"), search("unexpected"))
        assertEquals(listOf("Split"), search("\"unexpected\""))
    }

    @Test
    fun `a long body is cut to a window around the match`() {
        val filler = (1..80).joinToString(" ") { "word$it" }
        post("Long", body = "$filler emergent $filler")

        val match = searchSummaries("\"emergent\"").single().match!!
        assertEquals(true, match.text.startsWith("…"), match.text)
        assertEquals(true, match.text.endsWith("…"), match.text)
        assertEquals(listOf("emergent"), marked(searchSummaries("\"emergent\"").single()))
    }
}
