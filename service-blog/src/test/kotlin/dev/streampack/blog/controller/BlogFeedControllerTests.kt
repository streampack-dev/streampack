/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.controller

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.entity.User
import dev.streampack.core.model.Role
import dev.streampack.core.repository.UserRepository
import dev.streampack.test.TestChannelConfiguration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional

/** Integration tests for blog feed generation (Atom/RSS compatible assertions) */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Import(TestChannelConfiguration::class)
class BlogFeedControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository

    private lateinit var slugPath: String

    @BeforeEach
    fun setUp() {
        val now = Instant.now()
        val author =
            userRepository.save(
                User(
                    username = "feedauthor",
                    email = "feedauthor@test.com",
                    displayName = "Feed Author",
                    emailVerified = true,
                    role = Role.ADMIN,
                )
            )

        val post =
            postRepository.save(
                Post(
                    title = "Feed Test Post",
                    markdownSource = "Feed content",
                    renderedHtml = "<p>Feed content</p>",
                    excerpt = "A post for feed testing",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = author,
                )
            )

        val publishedAt = post.publishedAt!!
        val dateTime = publishedAt.atZone(ZoneOffset.UTC)
        slugPath = "${dateTime.year}/${"%02d".format(dateTime.monthValue)}/feed-test-post"
        slugRepository.save(Slug(path = slugPath, post = post, canonical = true))

        // Draft post should NOT appear in feed
        postRepository.save(
            Post(
                title = "Draft Feed Post",
                markdownSource = "Draft",
                renderedHtml = "<p>Draft</p>",
                status = PostStatus.DRAFT,
                author = author,
            )
        )

        // Approved system-style page should NOT appear in blog RSS entries
        val pagePost =
            postRepository.save(
                Post(
                    title = "About",
                    markdownSource = "About page",
                    renderedHtml = "<p>About page</p>",
                    excerpt = "About page excerpt",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(2, ChronoUnit.HOURS),
                    author = author,
                )
            )
        slugRepository.save(Slug(path = "about", post = pagePost, canonical = true))
    }

    @Test
    fun `feed returns valid feed XML with published posts`() {
        mockMvc.get("/feed.xml").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/rss+xml") }
            content { string(org.hamcrest.Matchers.containsString("<rss")) }
            content { string(org.hamcrest.Matchers.containsString("Feed Test Post")) }
            content { string(org.hamcrest.Matchers.containsString(slugPath)) }
            content { string(org.hamcrest.Matchers.containsString("Feed Author")) }
            content { string(org.hamcrest.Matchers.containsString("A post for feed testing")) }
            content {
                string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<p>Feed content</p>")
                    )
                )
            }
        }
    }

    @Test
    fun `feed atom endpoint returns atom xml`() {
        mockMvc.get("/feed.atom").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith("application/atom+xml") }
            content { string(org.hamcrest.Matchers.containsString("<feed")) }
            content { string(org.hamcrest.Matchers.containsString("Feed Test Post")) }
            content { string(org.hamcrest.Matchers.containsString("A post for feed testing")) }
            content {
                string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("<p>Feed content</p>")
                    )
                )
            }
        }
    }

    @Test
    fun `feed excludes draft posts`() {
        mockMvc.get("/feed.xml").andExpect {
            status { isOk() }
            content {
                string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Draft Feed Post")
                    )
                )
            }
        }
    }

    @Test
    fun `feed excludes non blog style slugs`() {
        mockMvc.get("/feed.xml").andExpect {
            status { isOk() }
            content {
                string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(">About</title>")
                    )
                )
            }
            content {
                string(
                    org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/posts/about"))
                )
            }
        }
    }

    @Test
    fun `feed includes channel metadata`() {
        mockMvc.get("/feed.xml").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("<title>Nevet</title>")) }
            content {
                string(
                    org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.containsString("<description>"),
                        org.hamcrest.Matchers.containsString("<subtitle>"),
                    )
                )
            }
        }
    }

    @Test
    fun `feed uses forwarded host and proto for public links`() {
        mockMvc
            .get("/feed.xml") {
                header("X-Forwarded-Proto", "https")
                header("X-Forwarded-Host", "foo.bytecode.news")
            }
            .andExpect {
                status { isOk() }
                content {
                    string(org.hamcrest.Matchers.containsString("https://foo.bytecode.news"))
                }
                content {
                    string(
                        org.hamcrest.Matchers.containsString(
                            "https://foo.bytecode.news/posts/$slugPath"
                        )
                    )
                }
                content {
                    string(
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("localhost:3001")
                        )
                    )
                }
            }
    }

    @Test
    fun `feed falls back to configured blog base url when forwarded host is absent`() {
        mockMvc
            .get("/feed.xml") { header("Host", "api.bytecode.news") }
            .andExpect {
                status { isOk() }
                content { string(org.hamcrest.Matchers.containsString("http://localhost:3001")) }
                content {
                    string(
                        org.hamcrest.Matchers.containsString(
                            "http://localhost:3001/posts/$slugPath"
                        )
                    )
                }
                content {
                    string(
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("https://api.bytecode.news")
                        )
                    )
                }
            }
    }

    @Test
    fun `feed falls back to derived summary when excerpt is missing`() {
        val now = Instant.now()
        val author =
            userRepository.findByUsername("feedauthor") ?: error("Expected test author to exist")

        val postWithoutExcerpt =
            postRepository.save(
                Post(
                    title = "No Excerpt Post",
                    markdownSource = "Derived summary body for feed fallback.",
                    renderedHtml = "<p>Derived summary body for feed fallback.</p>",
                    excerpt = null,
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(30, ChronoUnit.MINUTES),
                    author = author,
                )
            )
        val publishedAt = postWithoutExcerpt.publishedAt!!
        val dateTime = publishedAt.atZone(ZoneOffset.UTC)
        val fallbackSlug = "${dateTime.year}/${"%02d".format(dateTime.monthValue)}/no-excerpt-post"
        slugRepository.save(Slug(path = fallbackSlug, post = postWithoutExcerpt, canonical = true))

        mockMvc.get("/feed.xml").andExpect {
            status { isOk() }
            content {
                string(
                    org.hamcrest.Matchers.containsString("Derived summary body for feed fallback.")
                )
            }
            content {
                string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(
                            "<p>Derived summary body for feed fallback.</p>"
                        )
                    )
                )
            }
        }
    }
}
