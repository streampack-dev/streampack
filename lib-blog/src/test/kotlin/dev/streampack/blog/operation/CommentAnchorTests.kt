/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.entity.Comment
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.CommentAnchor
import dev.streampack.blog.model.CommentDetail
import dev.streampack.blog.model.CommentNode
import dev.streampack.blog.model.CommentThreadResponse
import dev.streampack.blog.model.CreateCommentRequest
import dev.streampack.blog.model.EditCommentRequest
import dev.streampack.blog.model.FindCommentsRequest
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.CommentRepository
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.blog.service.RenderedHtmlSanitizer
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
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

/** A comment can quote the passage of its post that it is about (#256). */
@SpringBootTest
@Transactional
class CommentAnchorTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var commentRepository: CommentRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var sanitizer: RenderedHtmlSanitizer

    private lateinit var reader: User
    private lateinit var post: Post

    @BeforeEach
    fun setUp() {
        val now = Instant.now()
        reader =
            userRepository.save(
                User(
                    username = "quoter",
                    email = "quoter@test.com",
                    displayName = "Quoter",
                    emailVerified = true,
                    role = Role.USER,
                ),
            )
        post =
            postRepository.save(
                Post(
                    title = "Anchored",
                    markdownSource = "unused",
                    renderedHtml =
                        "<p>The quick <em>brown</em> fox.</p>\n<p>Tom &amp; Jerry use &lt;b&gt; tags.</p>" +
                            "<ul><li>first item</li><li>second item</li></ul>",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minus(1, ChronoUnit.HOURS),
                    author = reader,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        slugRepository.save(Slug(path = "2026/10/anchored", post = post, canonical = true))
    }

    private fun <T : Any> send(payload: T): OperationResult =
        eventGateway.process(
            MessageBuilder.withPayload(payload)
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog-service",
                        replyTo = "comments",
                        user =
                            UserPrincipal(
                                id = reader.id,
                                username = reader.username,
                                displayName = reader.displayName,
                                role = reader.role,
                            ),
                    ),
                )
                .build(),
        ) as OperationResult

    private fun create(
        anchor: CommentAnchor?,
        parent: Comment? = null,
        markdown: String = "I agree.",
    ) = send(CreateCommentRequest(post.id, parent?.id, markdown, anchor))

    private fun created(anchor: CommentAnchor?) =
        (create(anchor) as OperationResult.Success).payload as CommentDetail

    private fun refusal(result: OperationResult) = (result as OperationResult.Error).message

    private fun thread() =
        ((send(FindCommentsRequest(post.id)) as OperationResult.Success).payload
                as CommentThreadResponse)
            .comments

    @Test
    fun `a comment with an anchor is stored with it and starts with the quote`() {
        val detail = created(CommentAnchor("brown fox.", prefix = "The quick ", suffix = " Tom"))

        assertThat(detail.anchor).isEqualTo(CommentAnchor("brown fox.", "The quick ", " Tom"))
        assertThat(detail.renderedHtml)
            .startsWith("<blockquote class=\"comment-anchor\"><p>brown fox.</p></blockquote>")
            .contains("I agree.")
        val stored = commentRepository.findById(detail.id).orElseThrow()
        assertThat(stored.anchorExact).isEqualTo("brown fox.")
        assertThat(stored.anchorPrefix).isEqualTo("The quick ")
        assertThat(stored.anchorSuffix).isEqualTo(" Tom")
        assertThat(stored.markdownSource).isEqualTo("I agree.")
    }

    @Test
    fun `a quote matches the text a reader sees, across tags, entities and paragraphs`() {
        // Inline tags go, entities are decoded, and block boundaries are whitespace.
        assertThat(created(CommentAnchor("quick brown fox")).anchor?.exact)
            .isEqualTo("quick brown fox")
        assertThat(created(CommentAnchor("Tom & Jerry use <b> tags")).anchor).isNotNull
        assertThat(created(CommentAnchor("fox.\n\n  Tom")).anchor?.exact).isEqualTo("fox. Tom")
        assertThat(created(CommentAnchor("first item\nsecond")).anchor?.exact)
            .isEqualTo("first item second")
    }

    @Test
    fun `the quote's whitespace is collapsed, exact is trimmed, and empty context becomes null`() {
        val anchor =
            created(CommentAnchor("  brown\tfox. ", prefix = "The  quick ", suffix = "")).anchor

        assertThat(anchor)
            .isEqualTo(CommentAnchor("brown fox.", prefix = "The quick ", suffix = null))
    }

    @Test
    fun `HTML in a quote is escaped, never rendered`() {
        val detail = created(CommentAnchor("<b> tags"))

        assertThat(detail.renderedHtml)
            .startsWith("<blockquote class=\"comment-anchor\"><p>&lt;b&gt; tags</p>")
        assertThat(detail.renderedHtml).doesNotContain("<b>")
    }

    @Test
    fun `a quoted comment is as the sanitizer leaves it, so the startup backfill doesn't change it`() {
        val quoting =
            postRepository.save(
                Post(
                    title = "Quotes",
                    renderedHtml =
                        "<p>She said &quot;it's 5 &lt; 6 &amp; done&quot;\u00a0today.</p>",
                    status = PostStatus.APPROVED,
                    publishedAt = post.publishedAt,
                    author = reader,
                ),
            )
        val result =
            send(
                CreateCommentRequest(
                    quoting.id,
                    null,
                    "Odd.",
                    CommentAnchor("said \"it's 5 < 6 & done\""),
                ),
            )
        val html = ((result as OperationResult.Success).payload as CommentDetail).renderedHtml

        assertThat(sanitizer.sanitize(html)).isEqualTo(html)
        assertThat(html).contains("5 &lt; 6 &amp; done")
    }

    @Test
    fun `a quote that isn't in the post is refused, and nothing is stored`() {
        val before = commentRepository.count()

        assertThat(refusal(create(CommentAnchor("lazy dog"))))
            .isEqualTo("The quoted passage isn't in the post")
        assertThat(commentRepository.count()).isEqualTo(before)
    }

    @Test
    fun `a quote over the limits, or empty, is refused`() {
        val longPost =
            postRepository.save(
                Post(
                    title = "Long",
                    renderedHtml = "<p>${"a".repeat(600)}</p>",
                    status = PostStatus.APPROVED,
                    publishedAt = post.publishedAt,
                    author = reader,
                ),
            )
        val long =
            send(
                CreateCommentRequest(
                    longPost.id,
                    null,
                    "Too long.",
                    CommentAnchor("a".repeat(501)),
                ),
            )

        assertThat(refusal(long)).isEqualTo("The quoted passage is longer than 500 characters")
        assertThat(refusal(create(CommentAnchor(" \n ")))).isEqualTo("The quoted passage is empty")
        assertThat(refusal(create(CommentAnchor("fox", prefix = "x".repeat(65)))))
            .isEqualTo("The quote's context is longer than 64 characters")
        assertThat(refusal(create(CommentAnchor("fox", suffix = "x".repeat(65)))))
            .isEqualTo("The quote's context is longer than 64 characters")
        // At the limits, it's accepted.
        val atLimit =
            send(
                CreateCommentRequest(
                    longPost.id,
                    null,
                    "Just right.",
                    CommentAnchor("a".repeat(500)),
                ),
            )
        assertThat(atLimit).isInstanceOf(OperationResult.Success::class.java)
    }

    @Test
    fun `a reply can't quote a passage`() {
        val parent = commentRepository.findById(created(null).id).orElseThrow()

        assertThat(refusal(create(CommentAnchor("brown fox"), parent = parent)))
            .isEqualTo("A reply can't quote a passage; its parent does")
    }

    @Test
    fun `the database refuses an anchor on a reply`() {
        val parent = commentRepository.findById(created(null).id).orElseThrow()

        assertThatThrownBy {
                commentRepository.saveAndFlush(
                    Comment(
                        post = post,
                        author = reader,
                        parentComment = parent,
                        anchorExact = "fox",
                    ),
                )
            }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `the database refuses a prefix with no quote`() {
        assertThatThrownBy {
                commentRepository.saveAndFlush(
                    Comment(post = post, author = reader, anchorPrefix = "The"),
                )
            }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `a comment without an anchor is as it was`() {
        val detail = created(null)

        assertThat(detail.anchor).isNull()
        assertThat(detail.renderedHtml).doesNotContain("comment-anchor")
        val node = thread().single()
        assertThat(node.anchor).isNull()
        assertThat(node.anchorFound).isNull()
    }

    @Test
    fun `an edit keeps the quote and changes only the words`() {
        val detail = created(CommentAnchor("brown fox."))

        val edited =
            (send(EditCommentRequest(detail.id, "On reflection, no.")) as OperationResult.Success)
                .payload as CommentDetail

        assertThat(edited.anchor).isEqualTo(CommentAnchor("brown fox."))
        assertThat(edited.renderedHtml)
            .startsWith("<blockquote class=\"comment-anchor\"><p>brown fox.</p></blockquote>")
            .contains("On reflection, no.")
            .doesNotContain("I agree.")
        assertThat(commentRepository.findById(detail.id).orElseThrow().markdownSource)
            .isEqualTo("On reflection, no.")
    }

    @Test
    fun `the thread says whether each quote is still in the post`() {
        created(CommentAnchor("brown fox."))

        val found = thread().single()
        assertThat(found.anchor?.exact).isEqualTo("brown fox.")
        assertThat(found.anchorFound).isTrue()

        postRepository.saveAndFlush(post.copy(renderedHtml = "<p>The quick red fox.</p>"))

        val orphan = thread().single()
        assertThat(orphan.anchorFound).isFalse()
        assertThat(orphan.anchor?.exact).isEqualTo("brown fox.")
        assertThat(orphan.renderedHtml)
            .startsWith("<blockquote class=\"comment-anchor\"><p>brown fox.</p>")
    }

    @Test
    fun `a deleted comment shows no anchor`() {
        val detail = created(CommentAnchor("brown fox."))
        commentRepository.saveAndFlush(
            commentRepository.findById(detail.id).orElseThrow().copy(deleted = true),
        )

        val node: CommentNode = thread().single()
        assertThat(node.deleted).isTrue()
        assertThat(node.anchor).isNull()
        assertThat(node.anchorFound).isNull()
    }
}
