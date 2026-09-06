/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.service

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.json.JacksonMappers
import dev.streampack.forge.client.WebhookEnvelope
import dev.streampack.forge.model.ForgeEvent
import dev.streampack.gitlab.config.GitLabProperties
import dev.streampack.gitlab.entity.GitLabInstance
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The GitLab-specific half of the forge contract: REST v4 calls and webhook parsing. */
class GitLabForgeClientTests {
    private val mapper = JacksonMappers.standard()
    private val client = GitLabForgeClient(GitLabApiClient(GitLabProperties()))
    private lateinit var httpServer: HttpServer
    private lateinit var instance: GitLabInstance

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
        instance =
            GitLabInstance(
                host = "gitlab.example.com",
                apiUrl = "http://localhost:${httpServer.address.port}/api/v4",
            )
    }

    @AfterEach fun tearDown() = httpServer.stop(0)

    private fun stub(
        path: String,
        body: String,
        status: Int = 200,
        seen: MutableList<String?>? = null,
    ) {
        httpServer.createContext(path) { exchange ->
            seen?.add(exchange.requestHeaders.getFirst("PRIVATE-TOKEN"))
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun `lookupProject encodes the path, sends the token, and returns the numeric id`() {
        val seen = mutableListOf<String?>()
        stub(
            "/api/v4/projects/group/sub/project",
            """{"id": 4242, "path_with_namespace": "group/sub/project", "web_url": "https://gitlab.example.com/group/sub/project"}""",
            seen = seen,
        )
        val ref = client.lookupProject(instance, "group/sub/project", "glpat-secret")
        assertNotNull(ref)
        assertEquals("group/sub/project", ref!!.path)
        assertEquals("4242", ref.externalId)
        assertEquals(listOf("glpat-secret"), seen)
    }

    @Test
    fun `lookupProject returns null for 404 and malformed paths`() {
        stub("/api/v4/projects/group/missing", "", status = 404)
        assertNull(client.lookupProject(instance, "group/missing", null))
        assertNull(client.lookupProject(instance, "no-namespace", null))
    }

    @Test
    fun `issues and merge requests are filtered above the cursor by iid`() {
        stub(
            "/api/v4/projects/group/project/issues",
            """[{"iid": 12, "title": "Newest", "web_url": "https://gitlab.example.com/group/project/-/issues/12"},
                {"iid": 11, "title": "Older", "web_url": "https://gitlab.example.com/group/project/-/issues/11"}]""",
        )
        stub(
            "/api/v4/projects/group/project/merge_requests",
            """[{"iid": 7, "title": "MR seven", "web_url": "https://gitlab.example.com/group/project/-/merge_requests/7"}]""",
        )
        val issues = client.fetchIssuesSince(instance, "group/project", null, 11)
        assertEquals(1, issues.size)
        assertEquals(12, issues[0].number)
        assertEquals("Newest", issues[0].title)
        val mrs = client.fetchChangeRequestsSince(instance, "group/project", null, 0)
        assertEquals(listOf(7), mrs.map { it.number })
    }

    @Test
    fun `releases read tag name and self link`() {
        stub(
            "/api/v4/projects/group/project/releases",
            """[{"tag_name": "v1.2.0", "name": "One point two", "_links": {"self": "https://gitlab.example.com/group/project/-/releases/v1.2.0"}}]""",
        )
        val releases = client.fetchReleases(instance, "group/project", null)
        assertEquals(1, releases.size)
        assertEquals("v1.2.0", releases[0].tag)
        assertEquals("One point two", releases[0].name)
        assertEquals("https://gitlab.example.com/group/project/-/releases/v1.2.0", releases[0].url)
    }

    @Test
    fun `api failures degrade to empty lists`() {
        stub("/api/v4/projects/group/project/issues", "", status = 500)
        assertTrue(client.fetchIssuesSince(instance, "group/project", null, 0).isEmpty())
    }

    @Test
    fun `webhook envelope needs the token and event headers`() {
        val headers =
            mapOf(
                "X-Gitlab-Token" to "hook-secret",
                "X-Gitlab-Event" to "Issue Hook",
                "X-Gitlab-Event-UUID" to "uuid-1",
            )
        val envelope = client.webhookEnvelope { headers[it] }
        assertEquals(WebhookEnvelope("Issue Hook", "uuid-1", "hook-secret"), envelope)
        assertNull(client.webhookEnvelope { if (it == "X-Gitlab-Event") "Issue Hook" else null })
        assertTrue(client.isSupportedWebhookEvent(envelope!!))
        assertTrue(client.isSupportedWebhookEvent(WebhookEnvelope("Merge Request Hook", null, "x")))
        assertTrue(client.isSupportedWebhookEvent(WebhookEnvelope("Release Hook", null, "x")))
        assertFalse(client.isSupportedWebhookEvent(WebhookEnvelope("Push Hook", null, "x")))
    }

    @Test
    fun `webhook verification compares the token with the stored secret`() {
        val envelope = WebhookEnvelope("Issue Hook", null, "hook-secret")
        assertTrue(client.verifyWebhook(envelope, "hook-secret", ByteArray(0)))
        assertFalse(client.verifyWebhook(envelope, "other-secret", ByteArray(0)))
        assertFalse(
            client.verifyWebhook(
                WebhookEnvelope("Issue Hook", null, null),
                "hook-secret",
                ByteArray(0),
            )
        )
    }

    @Test
    fun `webhook project ref carries the numeric id and the path`() {
        val root =
            mapper.readTree("""{"project": {"id": 4242, "path_with_namespace": "group/project"}}""")
        val ref = client.webhookProjectRef(root)!!
        assertEquals("group/project", ref.path)
        assertEquals("4242", ref.externalId)
        assertNull(client.webhookProjectRef(mapper.readTree("""{"project": {}}""")))
    }

    @Test
    fun `issue merge request and release hooks parse to forge events on open and create only`() {
        val issue =
            mapper.readTree(
                """{"object_kind":"issue","project":{"id":1,"path_with_namespace":"g/p"},
                    "object_attributes":{"iid":5,"title":"Broken","url":"https://gitlab.com/g/p/-/issues/5","action":"open"}}"""
            )
        val opened =
            assertInstanceOf(
                ForgeEvent.IssueOpened::class.java,
                client.parseWebhookEvent(WebhookEnvelope("Issue Hook", null, "t"), issue),
            )
        assertEquals(5, opened.item.number)
        assertEquals("Broken", opened.item.title)

        val closed =
            mapper.readTree(
                """{"object_kind":"issue","object_attributes":{"iid":5,"title":"Broken","url":"u","action":"close"}}"""
            )
        assertNull(client.parseWebhookEvent(WebhookEnvelope("Issue Hook", null, "t"), closed))

        val mr =
            mapper.readTree(
                """{"object_kind":"merge_request","object_attributes":{"iid":9,"title":"Fix it","url":"https://gitlab.com/g/p/-/merge_requests/9","action":"open"}}"""
            )
        val mrOpened =
            assertInstanceOf(
                ForgeEvent.ChangeRequestOpened::class.java,
                client.parseWebhookEvent(WebhookEnvelope("Merge Request Hook", null, "t"), mr),
            )
        assertEquals(9, mrOpened.item.number)

        val release =
            mapper.readTree(
                """{"object_kind":"release","action":"create","tag":"v2.0.0","name":"Two","url":"https://gitlab.com/g/p/-/releases/v2.0.0"}"""
            )
        val published =
            assertInstanceOf(
                ForgeEvent.ReleasePublished::class.java,
                client.parseWebhookEvent(WebhookEnvelope("Release Hook", null, "t"), release),
            )
        assertEquals("v2.0.0", published.release.tag)
        assertEquals("Two", published.release.name)

        val updated =
            mapper.readTree(
                """{"object_kind":"release","action":"update","tag":"v2.0.0","name":"Two","url":"u"}"""
            )
        assertNull(client.parseWebhookEvent(WebhookEnvelope("Release Hook", null, "t"), updated))
    }
}
