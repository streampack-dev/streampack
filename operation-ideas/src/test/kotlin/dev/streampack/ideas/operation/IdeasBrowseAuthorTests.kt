/* Joseph B. Ottinger (C)2026 */
package dev.streampack.ideas.operation

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.TagRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

/**
 * Listing ideas whose drafts have an author (#123). Deliberately not @Transactional: the operation
 * runs outside any transaction in the running bot, so a lazily loaded author must already be loaded
 * by the time the list names it.
 */
@SpringBootTest
class IdeasBrowseAuthorTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var userRepository: UserRepository

    private val created = mutableListOf<Any>()

    @AfterEach
    fun cleanup() {
        created.reversed().forEach {
            when (it) {
                is PostTag -> postTagRepository.delete(it)
                is Post -> postRepository.delete(it)
                is User -> userRepository.delete(it)
            }
        }
    }

    @Test
    fun `ideas lists drafts that have an author`() {
        val suffix = UUID.randomUUID().toString().take(8)
        val author =
            userRepository
                .save(
                    User(
                        username = "ideas-author-$suffix",
                        email = "ideas-author-$suffix@example.com",
                        displayName = "Idea Author",
                    )
                )
                .also { created += it }
        val tag =
            tagRepository.findByName("_idea")
                ?: tagRepository.save(Tag(name = "_idea", slug = "_idea"))
        val post =
            postRepository
                .save(
                    Post(
                        title = "An authored idea $suffix",
                        markdownSource = "Body",
                        renderedHtml = "<p>Body</p>",
                        status = PostStatus.DRAFT,
                        author = author,
                    )
                )
                .also { created += it }
        created += postTagRepository.save(PostTag(post = post, tag = tag))

        val admin =
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "admin",
                displayName = "Admin",
                role = Role.ADMIN,
            )
        val result =
            eventGateway.process(
                MessageBuilder.withPayload("ideas")
                    .setHeader(
                        Provenance.HEADER,
                        Provenance(
                            protocol = Protocol.CONSOLE,
                            serviceId = "",
                            replyTo = "local",
                            user = admin,
                        ),
                    )
                    .setHeader(Provenance.ADDRESSED, true)
                    .build()
            )

        assertInstanceOf(OperationResult.Success::class.java, result, result.toString())
    }
}
