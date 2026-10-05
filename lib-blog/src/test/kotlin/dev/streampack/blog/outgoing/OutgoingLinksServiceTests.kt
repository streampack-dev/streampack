/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.outgoing

import com.sun.net.httpserver.HttpServer
import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.core.model.LinkedSiteSubscription
import dev.streampack.core.model.LinkedSiteSubscriptionRequest
import dev.streampack.core.model.OperationOutcome
import dev.streampack.core.model.OperationResult
import dev.streampack.core.service.TypedOperation
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.Message

/**
 * A published post's outgoing links (#112, #128): each linked page told of the mention once, by
 * Webmention or Pingback, each linked site's feed asked for once, and both recorded in the post.
 */
@SpringBootTest(
    properties =
        [
            "streampack.blog.base-url=https://blog.example",
            "streampack.blog.outgoing.retry-delay=0s",
        ]
)
class OutgoingLinksServiceTests {
    /** Stands in for the RSS reader, keeping what it was asked. */
    @TestConfiguration
    class SubscriptionCapture {
        @Bean fun capturingSubscriptions() = CapturingSubscriptions()
    }

    class CapturingSubscriptions :
        TypedOperation<LinkedSiteSubscriptionRequest>(LinkedSiteSubscriptionRequest::class) {
        val requests = CopyOnWriteArrayList<LinkedSiteSubscriptionRequest>()
        override val addressed = false

        override fun handle(
            payload: LinkedSiteSubscriptionRequest,
            message: Message<*>,
        ): OperationOutcome {
            requests += payload
            return OperationResult.Success(
                LinkedSiteSubscription(LinkedSiteSubscription.Outcome.NO_FEED)
            )
        }
    }

    @Autowired lateinit var service: OutgoingLinksService
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository
    @Autowired lateinit var subscriptions: CapturingSubscriptions

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val webmentions = CopyOnWriteArrayList<Map<String, String>>()
    private val pingbacks = CopyOnWriteArrayList<String>()
    private val flakyCalls = AtomicInteger()
    private val created = mutableListOf<Post>()

    @BeforeEach
    fun start() {
        subscriptions.requests.clear()
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
        page("/wm-page", "<html>a post</html>", "Link" to "</wm-endpoint>; rel=\"webmention\"")
        page("/pb-page", "<html>a wordpress post</html>", "X-Pingback" to "$base/xmlrpc.php")
        page("/plain", "<html>nothing to tell</html>")
        page("/flaky-page", """<html><head><link rel="webmention" href="/flaky"></head></html>""")
        server.createContext("/wm-endpoint") { ex ->
            webmentions += form(ex.requestBody.readAllBytes().decodeToString())
            ex.sendResponseHeaders(202, -1)
            ex.close()
        }
        server.createContext("/xmlrpc.php") { ex ->
            pingbacks += ex.requestBody.readAllBytes().decodeToString()
            val ok =
                "<?xml version=\"1.0\"?><methodResponse><params><param><value><string>ok</string></value></param></params></methodResponse>"
                    .toByteArray()
            ex.sendResponseHeaders(200, ok.size.toLong())
            ex.responseBody.use { it.write(ok) }
        }
        server.createContext("/flaky") { ex ->
            ex.requestBody.readAllBytes()
            ex.sendResponseHeaders(if (flakyCalls.incrementAndGet() == 1) 503 else 202, -1)
            ex.close()
        }
    }

    @AfterEach
    fun stop() {
        server.stop(0)
        created.forEach { post ->
            slugRepository.findCanonical(post.id)?.let { slugRepository.delete(it) }
            postRepository.deleteById(post.id)
        }
    }

    private fun page(path: String, body: String, vararg headers: Pair<String, String>) {
        server.createContext(path) { ex ->
            headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            ex.responseHeaders.add("Content-Type", "text/html")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private fun form(body: String): Map<String, String> =
        body.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to URLDecoder.decode(v, Charsets.UTF_8)
        }

    private fun post(
        html: String,
        status: PostStatus = PostStatus.APPROVED,
        publishedAt: Instant = Instant.now().minusSeconds(60),
        metadata: Map<String, Any> = emptyMap(),
    ): Post {
        val saved =
            postRepository.save(
                Post(
                    title = "Outgoing",
                    markdownSource = "x",
                    renderedHtml = html,
                    status = status,
                    publishedAt = publishedAt,
                    metadata = metadata,
                )
            )
        slugRepository.save(
            Slug(path = "2026/10/outgoing-${UUID.randomUUID()}", post = saved, canonical = true)
        )
        created += saved
        return saved
    }

    private fun reload(post: Post) = postRepository.findById(post.id).get()

    @Suppress("UNCHECKED_CAST")
    private fun listOf(post: Post, key: String) = reload(post).metadata[key] as List<String>

    private fun links(vararg hrefs: String) =
        hrefs.joinToString("") { """<p><a href="$it">link</a></p>""" }

    @Test
    fun `a published post's links are mentioned, its sites looked at, and both recorded`() {
        val post =
            post(
                links(
                    "$base/wm-page",
                    "$base/pb-page",
                    "$base/plain#section",
                    "http://10.0.0.1/internal",
                    "https://blog.example/posts/2026/10/ours",
                    "https://pudl.blog.example/elsewhere",
                )
            )
        val source = "https://blog.example/posts/${slugRepository.findCanonical(post.id)!!.path}"

        service.handle(reload(post))

        assertEquals(listOf(mapOf("source" to source, "target" to "$base/wm-page")), webmentions)
        assertEquals(1, pingbacks.size)
        assertTrue(pingbacks[0].contains("<methodName>pingback.ping</methodName>"))
        assertTrue(pingbacks[0].contains("<string>$source</string>"))
        assertTrue(pingbacks[0].contains("<string>$base/pb-page</string>"))
        // Every link off the site recorded, whatever came of it; the site's own never.
        assertEquals(
            listOf("$base/wm-page", "$base/pb-page", "$base/plain", "http://10.0.0.1/internal"),
            listOf(post, "mentioned"),
        )
        // One request per site, with the first page linked there.
        assertEquals(
            listOf(
                LinkedSiteSubscriptionRequest("$base/wm-page", source),
                LinkedSiteSubscriptionRequest("http://10.0.0.1/internal", source),
            ),
            subscriptions.requests,
        )
        assertEquals(listOf("127.0.0.1", "10.0.0.1"), listOf(post, "feedsChecked"))
        // Handled, so not due again until it changes.
        assertEquals(
            post.updatedAt.toEpochMilli(),
            (reload(post).metadata["linksCheckedThrough"] as Number).toLong(),
        )
        assertFalse(
            postRepository.findOutgoingLinksDue(Instant.now(), 1000).any { it.id == post.id }
        )
    }

    @Test
    fun `a handled post isn't due again, whatever the fraction of a millisecond it was updated at`() {
        // .0006 s: rounded to the millisecond it would be .001, past the .000 recorded.
        val post =
            postRepository.save(
                post(links("$base/plain"))
                    .copy(updatedAt = Instant.parse("2026-10-01T00:00:00.000600Z"))
            )

        service.handle(reload(post))

        assertFalse(
            postRepository.findOutgoingLinksDue(Instant.now(), 1000).any { it.id == post.id }
        )
    }

    @Test
    fun `an edit mentions only the links it adds`() {
        val post = post(links("$base/wm-page"))
        service.handle(reload(post))
        webmentions.clear()

        val edited =
            postRepository.save(
                reload(post)
                    .copy(
                        renderedHtml = links("$base/wm-page", "$base/pb-page"),
                        updatedAt = Instant.now().plusSeconds(1),
                    )
            )
        assertTrue(
            postRepository.findOutgoingLinksDue(Instant.now(), 1000).any { it.id == post.id }
        )
        service.handle(edited)

        assertTrue(webmentions.isEmpty())
        assertEquals(1, pingbacks.size)
        // The site was looked at already.
        assertEquals(1, subscriptions.requests.size)
    }

    @Test
    fun `drafts aren't due, and a scheduled post is due once its time comes`() {
        val draft = post(links("$base/wm-page"), status = PostStatus.DRAFT)
        val later = Instant.now().plus(2, ChronoUnit.DAYS)
        val scheduled = post(links("$base/wm-page"), publishedAt = later)

        val now = postRepository.findOutgoingLinksDue(Instant.now(), 1000).map { it.id }
        assertFalse(draft.id in now)
        assertFalse(scheduled.id in now)
        assertTrue(
            scheduled.id in
                postRepository.findOutgoingLinksDue(later.plusSeconds(1), 1000).map { it.id }
        )
    }

    @Test
    fun `a post live before this began has its links recorded, not sent`() {
        val post = post(links("$base/wm-page"), metadata = mapOf("linksBaseline" to true))

        service.handle(reload(post))

        assertTrue(webmentions.isEmpty())
        assertTrue(subscriptions.requests.isEmpty())
        assertEquals(listOf("$base/wm-page"), listOf(post, "mentioned"))
        assertEquals(false, reload(post).metadata["linksBaseline"])
    }

    @Test
    fun `a mention that fails on the way is tried again`() {
        val post = post(links("$base/flaky-page"))

        service.handle(reload(post))

        assertEquals(2, flakyCalls.get())
    }
}
