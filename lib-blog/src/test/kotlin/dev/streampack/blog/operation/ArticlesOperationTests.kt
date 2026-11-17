/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper

/**
 * The `articles` chat command: newest posts, an author's posts, and search, over published posts
 * only. The cases are in `articles/cases.json`; the posts are made here.
 */
@SpringBootTest
@Transactional
class ArticlesOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository

    @BeforeEach
    fun setUp() {
        // Published as "dreamreal"; another user's username is "dreamreal".
        val epesh = user("epesh", "dreamreal")
        val ada = user("ada", "Ada Lovelace")
        val other = user("dreamreal", "Other Writer")
        val now = Instant.now()
        post(
            "Virtual Threads",
            "2026/11/virtual-threads",
            epesh,
            now.minus(1, ChronoUnit.HOURS),
            "Project Loom in production.",
        )
        post(
            "Records in Practice",
            "2026/11/records-in-practice",
            ada,
            now.minus(2, ChronoUnit.HOURS),
        )
        post("Pattern Matching", "2026/10/pattern-matching", other, now.minus(3, ChronoUnit.HOURS))
        post("Sealed Types", "2026/10/sealed-types", epesh, now.minus(4, ChronoUnit.HOURS))
        post("Spring Notes", "2026/10/spring-notes", ada, now.minus(5, ChronoUnit.HOURS))
        post("Secret Draft", "2026/11/secret-draft", epesh, null, status = PostStatus.DRAFT)
        post("Secret Future", "2026/12/secret-future", epesh, now.plus(7, ChronoUnit.DAYS))
    }

    private fun user(username: String, displayName: String): User =
        userRepository.save(
            User(
                username = username,
                email = "$username@test.com",
                displayName = displayName,
                emailVerified = true,
                role = Role.USER,
            ),
        )

    private fun post(
        title: String,
        path: String,
        author: User,
        publishedAt: Instant?,
        excerpt: String = "About ${title.lowercase()}.",
        status: PostStatus = PostStatus.APPROVED,
    ) {
        val post =
            postRepository.save(
                Post(
                    title = title,
                    markdownSource = excerpt,
                    renderedHtml = "<p>$excerpt</p>",
                    excerpt = excerpt,
                    status = status,
                    publishedAt = publishedAt,
                    author = author,
                ),
            )
        slugRepository.save(Slug(path = path, post = post, canonical = true))
    }

    private fun ask(text: String) =
        eventGateway.process(
            MessageBuilder.withPayload(text)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(protocol = Protocol.IRC, serviceId = "libera", replyTo = "#java"),
                )
                .build(),
        )

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `articles command`(name: String, command: String, expected: List<String>) {
        val result = ask(command)
        assertInstanceOf(OperationResult.Success::class.java, result, name)
        assertEquals(
            expected.joinToString("\n"),
            (result as OperationResult.Success).payload.toString(),
        )
    }

    @Test
    fun `other words are not taken`() {
        assertInstanceOf(OperationResult.NotHandled::class.java, ask("articlesx"))
    }

    companion object {
        @JvmStatic
        fun cases(): List<Arguments> {
            val json =
                ArticlesOperationTests::class.java.getResource("/articles/cases.json")?.readText()
                    ?: error("articles/cases.json is missing")
            val cases = JsonMapper.builder().build().readTree(json)
            return (0 until cases.size()).map { i ->
                val case = cases[i]
                Arguments.of(
                    case["name"].asString(),
                    case["command"].asString(),
                    (0 until case["expected"].size()).map { case["expected"][it].asString() },
                )
            }
        }
    }
}
