/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.TagRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.result.MockMvcResultHandlers.print

@SpringBootTest
@AutoConfigureMockMvc
@ResetDatabaseBeforeEach
@Import(TestChannelConfiguration::class)
class TaxonomyControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository

    private fun commandMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "test", replyTo = "local"),
            )
            .setHeader("nick", "tester")
            .build()

    @BeforeEach
    fun setUp() {
        val author =
            userRepository.save(
                User(
                    username = "taxonomy-author",
                    email = "taxonomy@author.test",
                    displayName = "Taxonomy Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        val postOne =
            postRepository.save(
                Post(
                    title = "Post One",
                    markdownSource = "Body one",
                    renderedHtml = "<p>Body one</p>",
                    excerpt = "One",
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now(),
                    author = author,
                )
            )
        val postTwo =
            postRepository.save(
                Post(
                    title = "Post Two",
                    markdownSource = "Body two",
                    renderedHtml = "<p>Body two</p>",
                    excerpt = "Two",
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now(),
                    author = author,
                )
            )

        val xyzTag = tagRepository.save(Tag(name = "xyz", slug = "xyz"))
        val kotlinTag = tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
        val sidebarTag = tagRepository.save(Tag(name = "_sidebar", slug = "sidebar-hidden"))
        postTagRepository.save(PostTag(post = postOne, tag = xyzTag))
        postTagRepository.save(PostTag(post = postOne, tag = kotlinTag))
        postTagRepository.save(PostTag(post = postTwo, tag = xyzTag))
        postTagRepository.save(PostTag(post = postTwo, tag = sidebarTag))

        val guidesCategory = categoryRepository.save(Category(name = "guides", slug = "guides"))
        val xyzCategory = categoryRepository.save(Category(name = "xyz", slug = "xyz-category"))
        val ideasCategory =
            categoryRepository.save(Category(name = "_ideas", slug = "ideas-hidden"))
        postCategoryRepository.save(PostCategory(post = postOne, category = guidesCategory))
        postCategoryRepository.save(PostCategory(post = postTwo, category = xyzCategory))
        postCategoryRepository.save(PostCategory(post = postTwo, category = ideasCategory))

        eventGateway.process(commandMessage("alpha=one"))
        eventGateway.process(commandMessage("alpha.tags=xyz,tools,_page"))
        eventGateway.process(commandMessage("beta=two"))
        eventGateway.process(commandMessage("beta.tags=xyz"))
    }

    @Test
    fun `GET taxonomy aggregates tags categories and aggregate counts`() {
        mockMvc
            .get("/taxonomy")
            .andDo { print() }
            .andExpect {
                status { isOk() }

                jsonPath("$.tags.xyz") { value(4) }
                jsonPath("$.tags.kotlin") { value(1) }
                jsonPath("$.tags.tools") { value(1) }
                jsonPath("$.tags._sidebar") { doesNotExist() }
                jsonPath("$.tags._page") { doesNotExist() }

                jsonPath("$.categories.guides") { value(1) }
                jsonPath("$.categories.xyz") { value(1) }
                jsonPath("$.categories._ideas") { doesNotExist() }

                jsonPath("$.aggregate.xyz") { value(5) }
                jsonPath("$.aggregate.guides") { value(1) }
                jsonPath("$.aggregate.kotlin") { value(1) }
                jsonPath("$.aggregate.tools") { value(1) }
                jsonPath("$.aggregate._sidebar") { doesNotExist() }
                jsonPath("$.aggregate._ideas") { doesNotExist() }
                jsonPath("$.aggregate._page") { doesNotExist() }
            }
    }
}
