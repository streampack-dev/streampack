/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.EditContentRequest
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.PostCategoryRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.PostTagRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.blog.repository.TagRepository
import dev.streampack.core.entity.User
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.core.repository.UserRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class EditContentOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository

    private lateinit var author: User
    private lateinit var admin: User
    private lateinit var otherUser: User
    private lateinit var draftPost: Post
    private lateinit var approvedPost: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "editauthor",
                    email = "editauthor@test.com",
                    displayName = "Edit Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.save(
                User(
                    username = "editadmin",
                    email = "editadmin@test.com",
                    displayName = "Edit Admin",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        otherUser =
            userRepository.save(
                User(
                    username = "editother",
                    email = "editother@test.com",
                    displayName = "Edit Other",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        draftPost =
            postRepository.save(
                Post(
                    title = "Original Draft Title",
                    markdownSource = "Original draft content.",
                    renderedHtml = "<p>Original draft content.</p>",
                    excerpt = "Original draft content.",
                    status = PostStatus.DRAFT,
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/original-draft-title", post = draftPost, canonical = true)
        )

        approvedPost =
            postRepository.save(
                Post(
                    title = "Approved Post",
                    markdownSource = "Approved content.",
                    renderedHtml = "<p>Approved content.</p>",
                    excerpt = "Approved content.",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/approved-post", post = approvedPost, canonical = true)
        )
    }

    private fun editMessage(request: EditContentRequest, user: User?) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "posts",
                    user =
                        user?.let {
                            UserPrincipal(
                                id = it.id,
                                username = it.username,
                                displayName = it.displayName,
                                role = it.role,
                            )
                        },
                ),
            )
            .build()

    @Test
    fun `admin edits draft successfully`() {
        val request =
            EditContentRequest(draftPost.id, "Updated Title", "# Updated\n\nNew content here.")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Updated Title", detail.title)
        assertTrue(detail.renderedHtml.contains("<h1>Updated</h1>"))
        assertTrue(detail.renderedHtml.contains("<p>New content here.</p>"))
        assertTrue(detail.excerpt!!.contains("New content here"))
    }

    @Test
    fun `provided summary overrides derived excerpt on edit`() {
        val request =
            EditContentRequest(
                id = draftPost.id,
                title = "Updated Title",
                markdownSource = "# Updated\n\nNew content here.",
                summary = "Manual edit summary.",
            )
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Manual edit summary.", detail.excerpt)
    }

    @Test
    fun `edit response includes markdownSource`() {
        val request =
            EditContentRequest(draftPost.id, "Updated Title", "# Updated\n\nNew content here.")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertNotNull(detail.markdownSource)
        assertEquals("# Updated\n\nNew content here.", detail.markdownSource)
    }

    @Test
    fun `admin edits any post including approved`() {
        val request =
            EditContentRequest(approvedPost.id, "Admin Edit", "Admin updated this content.")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Admin Edit", detail.title)
        assertEquals(PostStatus.APPROVED, detail.status)
    }

    @Test
    fun `non-author user cannot edit another's draft`() {
        val request = EditContentRequest(draftPost.id, "Hijacked", "I changed your post.")
        val result = eventGateway.process(editMessage(request, otherUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authorized to edit this post", (result as OperationResult.Error).message)
    }

    @Test
    fun `non-admin user cannot edit approved post`() {
        val request =
            EditContentRequest(approvedPost.id, "My Edit", "Trying to edit approved post.")
        val result = eventGateway.process(editMessage(request, author))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Not authorized to edit this post", (result as OperationResult.Error).message)
    }

    @Test
    fun `unauthenticated request returns error`() {
        val request = EditContentRequest(draftPost.id, "Anon Edit", "Anonymous attempt.")
        val result = eventGateway.process(editMessage(request, null))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Authentication required", (result as OperationResult.Error).message)
    }

    @Test
    fun `blank title returns error`() {
        val request = EditContentRequest(draftPost.id, "", "Content here.")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Title is required", (result as OperationResult.Error).message)
    }

    @Test
    fun `blank markdownSource returns error`() {
        val request = EditContentRequest(draftPost.id, "Title", "")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Content is required", (result as OperationResult.Error).message)
    }

    @Test
    fun `nonexistent post returns error`() {
        val request = EditContentRequest(UUID.randomUUID(), "Title", "Content")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `slug unchanged after admin edit`() {
        val request = EditContentRequest(draftPost.id, "Completely Different Title", "New content.")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("2026/02/original-draft-title", detail.slug)
    }

    @Test
    fun `edit falls back excerpt to title when markdown has no plain text`() {
        val request = EditContentRequest(draftPost.id, "Fallback Edit Title", "***")
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Fallback Edit Title", detail.excerpt)
    }

    @Test
    fun `edit with tags replaces existing tags`() {
        // First create a tag association
        val oldTag = tagRepository.save(Tag(name = "oldtag", slug = "oldtag"))
        postTagRepository.save(PostTag(post = draftPost, tag = oldTag))

        val request =
            EditContentRequest(
                draftPost.id,
                "Updated Title",
                "Updated content.",
                tags = listOf("newtag1", "newtag2"),
            )
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals(listOf("newtag1", "newtag2"), detail.tags)

        val postTags = postTagRepository.findByPost(draftPost.id)
        assertEquals(2, postTags.size)
    }

    @Test
    fun `edit with categories replaces existing categories`() {
        val cat1 = categoryRepository.save(Category(name = "Cat1", slug = "cat1"))
        val cat2 = categoryRepository.save(Category(name = "Cat2", slug = "cat2"))

        val request =
            EditContentRequest(
                draftPost.id,
                "Updated Title",
                "Updated content.",
                categoryIds = listOf(cat2.id),
            )
        val result = eventGateway.process(editMessage(request, admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals(listOf("Cat2"), detail.categories)

        val postCategories = postCategoryRepository.findByPost(draftPost.id)
        assertEquals(1, postCategories.size)
    }
}
