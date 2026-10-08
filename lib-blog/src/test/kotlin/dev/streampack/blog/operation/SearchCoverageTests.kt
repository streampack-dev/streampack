/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
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
                )
            )
    }

    private fun post(
        title: String,
        excerpt: String = "An unrelated summary",
        body: String = "Unrelated body text.",
        tags: List<String> = emptyList(),
    ): Post {
        val post =
            postRepository.save(
                Post(
                    title = title,
                    markdownSource = body,
                    renderedHtml = "<p>$body</p>",
                    excerpt = excerpt,
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now().minusSeconds(60),
                    author = author,
                )
            )
        tags.forEach { name ->
            val tag =
                tagRepository.findByName(name) ?: tagRepository.save(Tag(name = name, slug = name))
            postTagRepository.save(PostTag(post = post, tag = tag))
        }
        entityManager.flush()
        return post
    }

    private fun search(query: String): List<String> {
        val message =
            MessageBuilder.withPayload(FindContentRequest.Search(query))
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog-service",
                        replyTo = "posts",
                    ),
                )
                .build()
        val response =
            (eventGateway.process(message) as OperationResult.Success).payload
                as ContentListResponse
        assertEquals(
            response.posts.size.toLong(),
            response.totalCount,
            "count query agrees with results",
        )
        return response.posts.map { it.title }
    }

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
            PostTag(post = p, tag = tagRepository.save(Tag(name = "newtag", slug = "newtag")))
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
            p.copy(markdownSource = "Now about gradle.", renderedHtml = "<p>Now about gradle.</p>")
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
                "UPDATE posts SET search_vector = to_tsvector('simple', 'sentinel') WHERE id = :id"
            )
            .setParameter("id", p.id)
            .executeUpdate()
        entityManager.clear()

        // What RecordPostAccessOperation does: save the whole entity with a new access count.
        val loaded = postRepository.findById(p.id).orElseThrow()
        postRepository.save(
            loaded.copy(accessCount = loaded.accessCount + 1, lastAccessedAt = Instant.now())
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
}
