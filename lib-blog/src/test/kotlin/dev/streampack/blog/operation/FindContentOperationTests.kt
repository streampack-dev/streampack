/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Category
import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.PostCategory
import dev.streampack.blog.entity.PostTag
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.entity.Tag
import dev.streampack.blog.model.ContentDetail
import dev.streampack.blog.model.ContentListResponse
import dev.streampack.blog.model.FindContentRequest
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CategoryRepository
import dev.streampack.blog.repository.CommentRepository
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
import dev.streampack.temperature.service.TemperatureService
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class FindContentOperationTests {

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var commentRepository: CommentRepository
    @Autowired lateinit var tagRepository: TagRepository
    @Autowired lateinit var postTagRepository: PostTagRepository
    @Autowired lateinit var categoryRepository: CategoryRepository
    @Autowired lateinit var postCategoryRepository: PostCategoryRepository
    @Autowired lateinit var temperatureService: TemperatureService

    private lateinit var author: User
    private lateinit var admin: User
    private lateinit var superAdmin: User
    private lateinit var otherUser: User
    private lateinit var publishedPost: Post
    private lateinit var draftPost: Post
    private lateinit var scheduledPost: Post
    private lateinit var deletedPost: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()

        author =
            userRepository.save(
                User(
                    username = "author",
                    email = "author@test.com",
                    displayName = "Test Author",
                    emailVerified = true,
                    role = Role.USER,
                )
            )
        admin =
            userRepository.save(
                User(
                    username = "testadmin",
                    email = "testadmin@test.com",
                    displayName = "Admin User",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )
        superAdmin =
            userRepository.save(
                User(
                    username = "superadmin",
                    email = "superadmin@test.com",
                    displayName = "Super Admin",
                    emailVerified = true,
                    role = Role.SUPER_ADMIN,
                )
            )
        otherUser =
            userRepository.save(
                User(
                    username = "other",
                    email = "other@test.com",
                    displayName = "Other User",
                    emailVerified = true,
                    role = Role.USER,
                )
            )

        publishedPost =
            postRepository.save(
                Post(
                    title = "Published Post",
                    markdownSource = "# Published",
                    renderedHtml = "<h1>Published</h1>",
                    excerpt = "Published content",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/published-post", post = publishedPost, canonical = true)
        )

        draftPost =
            postRepository.save(
                Post(
                    title = "Draft Post",
                    markdownSource = "# Draft",
                    renderedHtml = "<h1>Draft</h1>",
                    excerpt = "Draft content",
                    status = PostStatus.DRAFT,
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "2026/02/draft-post", post = draftPost, canonical = true))

        scheduledPost =
            postRepository.save(
                Post(
                    title = "Scheduled Post",
                    markdownSource = "# Scheduled",
                    renderedHtml = "<h1>Scheduled</h1>",
                    excerpt = "Scheduled content",
                    status = PostStatus.APPROVED,
                    publishedAt = now.plus(7, ChronoUnit.DAYS),
                    author = author,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/scheduled-post", post = scheduledPost, canonical = true)
        )

        deletedPost =
            postRepository.save(
                Post(
                    title = "Deleted Post",
                    markdownSource = "# Deleted",
                    renderedHtml = "<h1>Deleted</h1>",
                    excerpt = "Deleted content",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(2, ChronoUnit.HOURS),
                    deleted = true,
                    author = author,
                )
            )
        slugRepository.save(
            Slug(path = "2026/02/deleted-post", post = deletedPost, canonical = true)
        )
    }

    private fun findMessage(request: FindContentRequest, user: User? = null) =
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
    fun `FindBySlug for published post returns ContentDetail`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/published-post"))
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Published Post", detail.title)
        assertEquals("2026/02/published-post", detail.slug)
        assertEquals("<h1>Published</h1>", detail.renderedHtml)
        assertEquals("Test Author", detail.authorDisplayName)
    }

    @Test
    fun `FindBySlug for nonexistent returns error`() {
        val result =
            eventGateway.process(findMessage(FindContentRequest.FindBySlug("2026/02/nonexistent")))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `FindBySlug for draft by non-author returns error`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/draft-post"), otherUser)
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `FindBySlug for draft by author returns ContentDetail`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/draft-post"), author)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Draft Post", detail.title)
        assertEquals(PostStatus.DRAFT, detail.status)
    }

    @Test
    fun `FindById for published post returns ContentDetail`() {
        val result =
            eventGateway.process(findMessage(FindContentRequest.FindById(publishedPost.id)))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Published Post", detail.title)
        assertNotNull(detail.publishedAt)
    }

    @Test
    fun `FindById for deleted post returns error`() {
        val result = eventGateway.process(findMessage(FindContentRequest.FindById(deletedPost.id)))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `FindById for draft by non-author returns error`() {
        val result =
            eventGateway.process(findMessage(FindContentRequest.FindById(draftPost.id), otherUser))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `FindPublished returns only published posts`() {
        val result = eventGateway.process(findMessage(FindContentRequest.FindPublished()))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        // Only the one published post should appear (not draft, scheduled, or deleted)
        assertEquals(1, response.posts.size)
        assertEquals("Published Post", response.posts[0].title)
        assertEquals(1, response.totalCount)
    }

    @Test
    fun `FindPublished pagination works`() {
        // Add more published posts
        val now = Instant.now()
        for (i in 1..5) {
            val post =
                postRepository.save(
                    Post(
                        title = "Extra Post $i",
                        markdownSource = "content $i",
                        renderedHtml = "<p>content $i</p>",
                        excerpt = "excerpt $i",
                        status = PostStatus.APPROVED,
                        publishedAt = now.minus(i.toLong(), ChronoUnit.HOURS),
                        author = author,
                    )
                )
            slugRepository.save(Slug(path = "2026/02/extra-post-$i", post = post, canonical = true))
        }

        // Request page 0, size 3 -- should get 3 of the 6 published posts
        val result =
            eventGateway.process(findMessage(FindContentRequest.FindPublished(page = 0, size = 3)))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(3, response.posts.size)
        assertEquals(0, response.page)
        assertEquals(2, response.totalPages)
        assertEquals(6, response.totalCount)
    }

    @Test
    fun `FindPopular returns published posts ordered by hit temperature`() {
        val now = Instant.now()
        val colderPost =
            postRepository.save(
                Post(
                    title = "Colder Post",
                    markdownSource = "colder",
                    renderedHtml = "<p>colder</p>",
                    excerpt = "colder",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(2, ChronoUnit.HOURS),
                    author = author,
                )
            )
        val hotterPost =
            postRepository.save(
                Post(
                    title = "Hotter Post",
                    markdownSource = "hotter",
                    renderedHtml = "<p>hotter</p>",
                    excerpt = "hotter",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(3, ChronoUnit.HOURS),
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "2026/02/colder-post", post = colderPost, canonical = true))
        slugRepository.save(Slug(path = "2026/02/hotter-post", post = hotterPost, canonical = true))

        temperatureService.accrue(
            "blog.post",
            publishedPost.id.toString(),
            "hit",
            positiveDelta = 3L,
        )
        temperatureService.accrue("blog.post", colderPost.id.toString(), "hit", positiveDelta = 1L)
        temperatureService.accrue("blog.post", hotterPost.id.toString(), "hit", positiveDelta = 5L)

        val result =
            eventGateway.process(findMessage(FindContentRequest.FindPopular(page = 0, size = 2)))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(2, response.posts.size)
        assertEquals("Hotter Post", response.posts[0].title)
        assertEquals("Published Post", response.posts[1].title)
        assertEquals(0, response.page)
        assertEquals(2, response.totalPages)
        assertEquals(3, response.totalCount)
    }

    @Test
    fun `admin can see draft via FindById`() {
        val result =
            eventGateway.process(findMessage(FindContentRequest.FindById(draftPost.id), admin))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Draft Post", detail.title)
        assertEquals(PostStatus.DRAFT, detail.status)
    }

    @Test
    fun `anonymous user cannot see draft via FindBySlug`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/draft-post"), null)
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `anonymous user cannot see scheduled post via FindBySlug`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/scheduled-post"), null)
            )

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Post not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `author can see scheduled post via FindBySlug`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/scheduled-post"), author)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Scheduled Post", detail.title)
    }

    @Test
    fun `commentCount reflects active comments at any nesting depth`() {
        // Add top-level and nested comments, plus one soft-deleted
        val topComment =
            commentRepository.save(
                Comment(
                    post = publishedPost,
                    author = otherUser,
                    markdownSource = "Top comment",
                    renderedHtml = "<p>Top comment</p>",
                )
            )
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = author,
                markdownSource = "Nested reply",
                renderedHtml = "<p>Nested reply</p>",
                parentComment = topComment,
            )
        )
        commentRepository.save(
            Comment(
                post = publishedPost,
                author = otherUser,
                markdownSource = "Deleted comment",
                renderedHtml = "<p>Deleted comment</p>",
                deleted = true,
            )
        )

        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/published-post"))
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        // 2 active comments (top-level + nested), soft-deleted excluded
        assertEquals(2, detail.commentCount)
    }

    @Test
    fun `commentCount is zero when no comments exist`() {
        val result =
            eventGateway.process(findMessage(FindContentRequest.FindById(publishedPost.id)))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals(0, detail.commentCount)
    }

    @Test
    fun `FindBySlug includes tags and categories`() {
        val tag = tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
        val category = categoryRepository.save(Category(name = "JVM", slug = "jvm"))
        postTagRepository.save(PostTag(post = publishedPost, tag = tag))
        postCategoryRepository.save(PostCategory(post = publishedPost, category = category))

        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/published-post"))
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals(listOf("kotlin"), detail.tags)
        assertEquals(listOf("JVM"), detail.categories)
    }

    @Test
    fun `FindPublished includes tags and categories in summaries`() {
        val tag = tagRepository.save(Tag(name = "spring", slug = "spring"))
        postTagRepository.save(PostTag(post = publishedPost, tag = tag))

        val result = eventGateway.process(findMessage(FindContentRequest.FindPublished()))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        val summary = response.posts.first { it.title == "Published Post" }
        assertEquals(listOf("spring"), summary.tags)
    }

    @Test
    fun `Search finds posts by title`() {
        val result = eventGateway.process(findMessage(FindContentRequest.Search("Published")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(1, response.posts.size)
        assertEquals("Published Post", response.posts[0].title)
    }

    @Test
    fun `Search finds posts by excerpt content`() {
        val result =
            eventGateway.process(findMessage(FindContentRequest.Search("Published content")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(1, response.posts.size)
    }

    @Test
    fun `Search returns empty for no matches`() {
        val result = eventGateway.process(findMessage(FindContentRequest.Search("xyznonexistent")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(0, response.posts.size)
        assertEquals(0, response.totalCount)
    }

    @Test
    fun `Search ignores drafts and deleted posts`() {
        val result = eventGateway.process(findMessage(FindContentRequest.Search("Draft")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        // Draft and deleted posts should not appear
        assertEquals(0, response.posts.size)
    }

    @Test
    fun `anonymous user does not receive markdownSource`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/published-post"))
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertNull(detail.markdownSource)
    }

    @Test
    fun `other user does not receive markdownSource`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/published-post"), otherUser)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertNull(detail.markdownSource)
    }

    @Test
    fun `author does not receive markdownSource for own post`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/published-post"), author)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertNull(detail.markdownSource)
    }

    @Test
    fun `admin receives markdownSource for any post`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/published-post"), admin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("# Published", detail.markdownSource)
    }

    @Test
    fun `super admin receives markdownSource for any post`() {
        val result =
            eventGateway.process(
                findMessage(FindContentRequest.FindBySlug("2026/02/published-post"), superAdmin)
            )

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("# Published", detail.markdownSource)
    }

    @Test
    fun `FindById as author excludes markdownSource`() {
        val result =
            eventGateway.process(findMessage(FindContentRequest.FindById(publishedPost.id), author))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertNull(detail.markdownSource)
    }

    @Test
    fun `FindById as anonymous excludes markdownSource`() {
        val result =
            eventGateway.process(findMessage(FindContentRequest.FindById(publishedPost.id)))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertNull(detail.markdownSource)
    }

    @Test
    fun `Search with blank query returns empty`() {
        val result = eventGateway.process(findMessage(FindContentRequest.Search("  ")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(0, response.posts.size)
    }

    @Test
    fun `FindByCategory returns posts in named category`() {
        val category = categoryRepository.save(Category(name = "kotlin", slug = "kotlin"))
        postCategoryRepository.save(PostCategory(post = publishedPost, category = category))

        val result = eventGateway.process(findMessage(FindContentRequest.FindByCategory("kotlin")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(1, response.posts.size)
        assertEquals("Published Post", response.posts[0].title)
    }

    @Test
    fun `FindByCategory returns empty for category with no posts`() {
        categoryRepository.save(Category(name = "empty-cat", slug = "empty-cat"))

        val result =
            eventGateway.process(findMessage(FindContentRequest.FindByCategory("empty-cat")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(0, response.posts.size)
        assertEquals(0, response.totalCount)
    }

    @Test
    fun `FindByTag returns posts with matching tag`() {
        val tag = tagRepository.save(Tag(name = "kotlin", slug = "kotlin"))
        postTagRepository.save(PostTag(post = publishedPost, tag = tag))

        val result = eventGateway.process(findMessage(FindContentRequest.FindByTag("kotlin")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(1, response.posts.size)
        assertEquals("Published Post", response.posts[0].title)
    }

    @Test
    fun `FindByTag returns empty for tag with no posts`() {
        tagRepository.save(Tag(name = "empty-tag", slug = "empty-tag"))

        val result = eventGateway.process(findMessage(FindContentRequest.FindByTag("empty-tag")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        assertEquals(0, response.posts.size)
        assertEquals(0, response.totalCount)
    }

    @Test
    fun `FindPage returns system page by slug`() {
        val pagesCategory = categoryRepository.findByName("_pages")!!
        val now = Instant.now()
        val aboutPost =
            postRepository.save(
                Post(
                    title = "About",
                    markdownSource = "# About Us",
                    renderedHtml = "<h1>About Us</h1>",
                    excerpt = "About this site",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = admin,
                )
            )
        slugRepository.save(Slug(path = "about", post = aboutPost, canonical = true))
        postCategoryRepository.save(PostCategory(post = aboutPost, category = pagesCategory))

        val result = eventGateway.process(findMessage(FindContentRequest.FindPage("about")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("About", detail.title)
        assertEquals("<h1>About Us</h1>", detail.renderedHtml)
    }

    @Test
    fun `FindPage returns page from any system category`() {
        val sidebarCategory = categoryRepository.findByName("_sidebar")!!
        val now = Instant.now()
        val sidebarPost =
            postRepository.save(
                Post(
                    title = "Sidebar Page",
                    markdownSource = "# Sidebar",
                    renderedHtml = "<h1>Sidebar</h1>",
                    excerpt = "Sidebar content",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = admin,
                )
            )
        slugRepository.save(Slug(path = "sidebar-page", post = sidebarPost, canonical = true))
        postCategoryRepository.save(PostCategory(post = sidebarPost, category = sidebarCategory))

        val result = eventGateway.process(findMessage(FindContentRequest.FindPage("sidebar-page")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("Sidebar Page", detail.title)
    }

    @Test
    fun `FindPage returns error for nonexistent page`() {
        val result = eventGateway.process(findMessage(FindContentRequest.FindPage("no-such-page")))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Page not found", (result as OperationResult.Error).message)
    }

    @Test
    fun `FindBySlug uses requested path when canonical slug missing`() {
        val now = Instant.now()
        val post =
            postRepository.save(
                Post(
                    title = "Alias Only Post",
                    markdownSource = "# Alias",
                    renderedHtml = "<h1>Alias</h1>",
                    excerpt = "alias only",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(10, ChronoUnit.MINUTES),
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "2026/03/alias-only", post = post, canonical = false))

        val result =
            eventGateway.process(findMessage(FindContentRequest.FindBySlug("2026/03/alias-only")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("2026/03/alias-only", detail.slug)
    }

    @Test
    fun `FindById uses empty slug when canonical slug missing`() {
        val now = Instant.now()
        val post =
            postRepository.save(
                Post(
                    title = "Id Only Post",
                    markdownSource = "# ID",
                    renderedHtml = "<h1>ID</h1>",
                    excerpt = "id only",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(10, ChronoUnit.MINUTES),
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "2026/03/id-only", post = post, canonical = false))

        val result = eventGateway.process(findMessage(FindContentRequest.FindById(post.id)))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("", detail.slug)
    }

    @Test
    fun `FindPage uses requested slug when canonical slug missing`() {
        val pagesCategory = categoryRepository.findByName("_pages")!!
        val now = Instant.now()
        val page =
            postRepository.save(
                Post(
                    title = "Alias Page",
                    markdownSource = "# Alias Page",
                    renderedHtml = "<h1>Alias Page</h1>",
                    excerpt = "alias page",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(10, ChronoUnit.MINUTES),
                    author = admin,
                )
            )
        slugRepository.save(Slug(path = "alias-page", post = page, canonical = false))
        postCategoryRepository.save(PostCategory(post = page, category = pagesCategory))

        val result = eventGateway.process(findMessage(FindContentRequest.FindPage("alias-page")))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val detail = (result as OperationResult.Success).payload as ContentDetail
        assertEquals("alias-page", detail.slug)
    }

    @Test
    fun `FindPublished excludes posts in system categories`() {
        val sidebarCategory = categoryRepository.findByName("_sidebar")!!
        val now = Instant.now()
        val sidebarPost =
            postRepository.save(
                Post(
                    title = "Sidebar Widget",
                    markdownSource = "sidebar content",
                    renderedHtml = "<p>sidebar content</p>",
                    excerpt = "sidebar",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(30, ChronoUnit.MINUTES),
                    author = admin,
                )
            )
        postCategoryRepository.save(PostCategory(post = sidebarPost, category = sidebarCategory))

        val result = eventGateway.process(findMessage(FindContentRequest.FindPublished()))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as ContentListResponse
        // Only the original published post, not the sidebar widget
        assertEquals(1, response.posts.size)
        assertEquals("Published Post", response.posts[0].title)
    }
}
