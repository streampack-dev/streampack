/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

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
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.taxonomy.model.FindBlogCategoryTaxonomyRequest
import dev.streampack.taxonomy.model.FindBlogTagTaxonomyRequest
import dev.streampack.taxonomy.model.TaxonomyTermCount
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class FindBlogTaxonomyOperationsTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository

    private fun message(payload: Any) =
        MessageBuilder.withPayload(payload)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, serviceId = "test", replyTo = "local"),
            )
            .build()

    @BeforeEach
    fun setUp() {
        val author =
            userRepository.save(
                User(
                    username = "taxonomy-blog-author",
                    email = "taxonomy-blog@author.test",
                    displayName = "Taxonomy Blog Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        val postOne =
            postRepository.save(
                Post(
                    title = "Taxonomy Post 1",
                    markdownSource = "Body 1",
                    renderedHtml = "<p>Body 1</p>",
                    excerpt = "One",
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now(),
                    author = author,
                )
            )
        val postTwo =
            postRepository.save(
                Post(
                    title = "Taxonomy Post 2",
                    markdownSource = "Body 2",
                    renderedHtml = "<p>Body 2</p>",
                    excerpt = "Two",
                    status = PostStatus.APPROVED,
                    publishedAt = Instant.now(),
                    author = author,
                )
            )

        val xyzTag = tagRepository.save(Tag(name = "xyz", slug = "xyz"))
        val kotlinTag = tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
        val hiddenTag = tagRepository.save(Tag(name = "_sidebar", slug = "sidebar-hidden"))
        postTagRepository.save(PostTag(post = postOne, tag = xyzTag))
        postTagRepository.save(PostTag(post = postOne, tag = kotlinTag))
        postTagRepository.save(PostTag(post = postTwo, tag = xyzTag))
        postTagRepository.save(PostTag(post = postTwo, tag = hiddenTag))

        val guidesCategory = categoryRepository.save(Category(name = "guides", slug = "guides"))
        val xyzCategory = categoryRepository.save(Category(name = "xyz", slug = "xyz-category"))
        val hiddenCategory =
            categoryRepository.save(Category(name = "_ideas", slug = "ideas-hidden"))
        postCategoryRepository.save(PostCategory(post = postOne, category = guidesCategory))
        postCategoryRepository.save(PostCategory(post = postTwo, category = xyzCategory))
        postCategoryRepository.save(PostCategory(post = postTwo, category = hiddenCategory))
    }

    @Test
    fun `blog tag taxonomy returns grouped counts and excludes underscore terms`() {
        val result = eventGateway.process(message(FindBlogTagTaxonomyRequest))
        assertInstanceOf(OperationResult.Success::class.java, result)

        val tags = (result as OperationResult.Success).payload as List<*>
        val counts = tags.filterIsInstance<TaxonomyTermCount>().associate { it.name to it.count }

        assertEquals(2L, counts["xyz"])
        assertEquals(1L, counts["kotlin"])
        assertTrue("_sidebar" !in counts.keys)
    }

    @Test
    fun `blog category taxonomy returns grouped counts and excludes underscore terms`() {
        val result = eventGateway.process(message(FindBlogCategoryTaxonomyRequest))
        assertInstanceOf(OperationResult.Success::class.java, result)

        val categories = (result as OperationResult.Success).payload as List<*>
        val counts =
            categories.filterIsInstance<TaxonomyTermCount>().associate { it.name to it.count }

        assertEquals(1L, counts["guides"])
        assertEquals(1L, counts["xyz"])
        assertTrue("_ideas" !in counts.keys)
    }
}
