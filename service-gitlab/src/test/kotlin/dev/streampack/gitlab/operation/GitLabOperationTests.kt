/* Joseph B. Ottinger (C)2026 */
package dev.streampack.gitlab.operation

import com.sun.net.httpserver.HttpServer
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.forge.model.DeliveryMode
import dev.streampack.gitlab.entity.GitLabInstance
import dev.streampack.gitlab.model.AddProjectRequest
import dev.streampack.gitlab.model.GitLabWebhookEnableRequest
import dev.streampack.gitlab.repository.GitLabInstanceRepository
import dev.streampack.gitlab.repository.GitLabProjectRepository
import dev.streampack.gitlab.service.GitLabForgeStore
import dev.streampack.gitlab.service.GitLabWebhookSecretCipher
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.net.InetSocketAddress
import java.util.UUID
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.messaging.support.MessageBuilder
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** The `gitlab ...` command surface end to end against a stubbed REST v4 server. */
@SpringBootTest(
    properties =
        [
            "streampack.gitlab.webhook-base-url=https://hooks.example.com",
            "GITLAB_INSTANCE_GITLAB_EXAMPLE_COM_TOKEN=glpat-instance-default",
        ]
)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
@ResourceLock("gitlab-api-endpoint")
class GitLabOperationTests {
    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var instanceRepository: GitLabInstanceRepository
    @Autowired lateinit var projectRepository: GitLabProjectRepository
    @Autowired lateinit var store: GitLabForgeStore
    @Autowired lateinit var cipher: GitLabWebhookSecretCipher
    @Autowired lateinit var addOperation: GitLabAddOperation
    @Autowired lateinit var webhookOperation: GitLabWebhookOperation
    @Autowired lateinit var handlerMapping: RequestMappingHandlerMapping

    private lateinit var httpServer: HttpServer

    private val adminUser =
        UserPrincipal(
            id = UUID.randomUUID(),
            username = "admin",
            displayName = "Admin",
            role = Role.ADMIN,
        )

    private fun adminMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.CONSOLE,
                    serviceId = "",
                    replyTo = "local",
                    user = adminUser,
                ),
            )
            .build()

    private fun guestMessage(text: String) =
        MessageBuilder.withPayload(text)
            .setHeader(
                Provenance.HEADER,
                Provenance(protocol = Protocol.CONSOLE, replyTo = "local"),
            )
            .build()

    @BeforeEach
    fun setUp() {
        httpServer = HttpServer.create(InetSocketAddress(0), 0)
        httpServer.start()
    }

    @AfterEach fun tearDown() = httpServer.stop(0)

    private fun apiUrl() = "http://localhost:${httpServer.address.port}/api/v4"

    private fun stubProject(path: String, id: Long, seen: MutableList<String?> = mutableListOf()) {
        httpServer.createContext("/api/v4/projects/$path") { exchange ->
            seen.add(exchange.requestHeaders.getFirst("PRIVATE-TOKEN"))
            val sub = exchange.requestURI.path.removePrefix("/api/v4/projects/$path")
            val body =
                when (sub) {
                    "" ->
                        """{"id": $id, "path_with_namespace": "$path", "web_url": "https://gitlab.example.com/$path"}"""
                    "/issues" ->
                        """[{"iid": 3, "title": "Issue three", "web_url": "https://gitlab.example.com/$path/-/issues/3"}]"""
                    "/merge_requests" ->
                        """[{"iid": 2, "title": "MR two", "web_url": "https://gitlab.example.com/$path/-/merge_requests/2"}]"""
                    "/releases" ->
                        """[{"tag_name": "v1.0.0", "name": "First", "_links": {"self": "https://gitlab.example.com/$path/-/releases/v1.0.0"}}]"""
                    else -> "[]"
                }
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
    }

    @Test
    fun `parsing covers nested paths tokens and on host`() {
        assertEquals(
            AddProjectRequest("group/sub/project", null, null),
            addOperation.translate("gitlab add group/sub/project", message()),
        )
        assertEquals(
            AddProjectRequest("group/sub/project", "glpat-x", "gitlab.example.com"),
            addOperation.translate(
                "gitlab add group/sub/project glpat-x on GitLab.Example.com",
                message(),
            ),
        )
        assertNull(addOperation.translate("gitlab add", message()))
        assertEquals(
            GitLabWebhookEnableRequest(
                "group/project",
                privateMode = true,
                host = "gitlab.example.com",
            ),
            webhookOperation.translate(
                "gitlab webhook private group/project on gitlab.example.com",
                message(),
            ),
        )
        assertEquals(
            GitLabWebhookEnableRequest("group/project", privateMode = false, host = null),
            webhookOperation.translate("gitlab webhook group/project", message()),
        )
    }

    private fun message() = MessageBuilder.withPayload("").build()

    @Test
    fun `instance list shows gitlab dot com and instance add derives the api url`() {
        val listed = eventGateway.process(guestMessage("gitlab instance list"))
        val payload =
            assertInstanceOf(OperationResult.Success::class.java, listed).payload.toString()
        assertTrue(payload.contains("gitlab.com -> https://gitlab.com/api/v4"), payload)

        assertInstanceOf(
            OperationResult.NotHandled::class.java,
            eventGateway.process(guestMessage("gitlab instance add https://gitlab.example.com")),
        )
        val added =
            eventGateway.process(
                adminMessage("gitlab instance add https://gitlab.example.com glpat-literal")
            )
        val addedPayload =
            assertInstanceOf(OperationResult.Success::class.java, added).payload.toString()
        assertTrue(addedPayload.contains("GITLAB_INSTANCE_GITLAB_EXAMPLE_COM_TOKEN"), addedPayload)
        val instance = instanceRepository.findByHost("gitlab.example.com")
        assertNotNull(instance)
        assertEquals("https://gitlab.example.com/api/v4", instance!!.apiUrl)
        assertEquals("glpat-literal", instance.defaultToken?.asStoredValue())
    }

    @Test
    fun `add subscribe list remove on both instances with separate endpoints and tokens`() {
        val hostedSeen = mutableListOf<String?>()
        val selfSeen = mutableListOf<String?>()
        stubProject("group/project", 100, hostedSeen)
        stubProject("other/project", 200, selfSeen)
        instanceRepository.save(store.defaultInstance().copy(apiUrl = apiUrl()))
        instanceRepository.save(
            GitLabInstance(
                host = "gitlab.example.com",
                apiUrl = apiUrl(),
                defaultToken =
                    dev.streampack.core.model.SecretRef.env(
                        "GITLAB_INSTANCE_GITLAB_EXAMPLE_COM_TOKEN"
                    ),
            )
        )

        val hosted = eventGateway.process(adminMessage("gitlab add group/project"))
        val hostedPayload =
            assertInstanceOf(OperationResult.Success::class.java, hosted).payload.toString()
        assertTrue(
            hostedPayload.contains("Watching group/project (1 issues, 1 MRs, 1 releases)"),
            hostedPayload,
        )
        val stored =
            projectRepository.findByInstanceAndFullPath(store.defaultInstance(), "group/project")!!
        assertEquals(100L, stored.projectId)
        assertEquals(3, stored.highestIssueNumber)
        assertEquals(2, stored.highestMrNumber)

        val self =
            eventGateway.process(adminMessage("gitlab add other/project on gitlab.example.com"))
        val selfPayload =
            assertInstanceOf(OperationResult.Success::class.java, self).payload.toString()
        assertTrue(selfPayload.contains("Watching gitlab.example.com other/project"), selfPayload)
        assertTrue(hostedSeen.isNotEmpty() && hostedSeen.all { it == null }, hostedSeen.toString())
        assertTrue(
            selfSeen.isNotEmpty() && selfSeen.all { it == "glpat-instance-default" },
            selfSeen.toString(),
        )

        assertTrue(
            assertInstanceOf(
                    OperationResult.Error::class.java,
                    eventGateway.process(adminMessage("gitlab add x/y on nowhere.example.com")),
                )
                .message
                .contains("nowhere.example.com")
        )
        assertTrue(
            assertInstanceOf(
                    OperationResult.Error::class.java,
                    eventGateway.process(adminMessage("gitlab add noslash")),
                )
                .message
                .contains("group/project")
        )

        val subscribed =
            eventGateway.process(
                adminMessage("gitlab subscribe other/project on gitlab.example.com")
            )
        assertEquals(
            "Subscribed to gitlab.example.com other/project",
            assertInstanceOf(OperationResult.Success::class.java, subscribed).payload,
        )
        assertInstanceOf(
            OperationResult.Error::class.java,
            eventGateway.process(adminMessage("gitlab subscribe other/project")),
        )

        val list =
            assertInstanceOf(
                    OperationResult.Success::class.java,
                    eventGateway.process(guestMessage("gitlab list")),
                )
                .payload
                .toString()
        assertTrue(
            list.contains("group/project") && list.contains("gitlab.example.com other/project"),
            list,
        )
        val subs =
            assertInstanceOf(
                    OperationResult.Success::class.java,
                    eventGateway.process(guestMessage("gitlab subscriptions")),
                )
                .payload
                .toString()
        assertTrue(subs.contains("gitlab.example.com other/project"), subs)

        val removed =
            eventGateway.process(adminMessage("gitlab remove other/project on gitlab.example.com"))
        val removedPayload =
            assertInstanceOf(OperationResult.Success::class.java, removed).payload.toString()
        assertTrue(
            removedPayload.contains(
                "Removed gitlab.example.com other/project (1 subscriptions deactivated)"
            ),
            removedPayload,
        )
    }

    @Test
    fun `webhook enable stores an encrypted token and prints the route for the instance`() {
        stubProject("group/project", 100)
        instanceRepository.save(store.defaultInstance().copy(apiUrl = apiUrl()))
        val hosted = eventGateway.process(adminMessage("gitlab webhook group/project"))
        val hostedPayload =
            assertInstanceOf(OperationResult.Success::class.java, hosted).payload.toString()
        assertTrue(
            hostedPayload.contains("https://hooks.example.com/webhooks/gitlab "),
            hostedPayload,
        )
        assertTrue(hostedPayload.contains("Secret token"), hostedPayload)
        val project =
            projectRepository.findByInstanceAndFullPath(store.defaultInstance(), "group/project")!!
        assertEquals(DeliveryMode.WEBHOOK, project.deliveryMode)
        assertEquals(64, cipher.decrypt(project.webhookSecret!!).length)

        val self =
            instanceRepository.save(
                GitLabInstance(
                    host = "gitlab.example.com",
                    apiUrl = "https://gitlab.example.com/api/v4",
                )
            )
        val private =
            eventGateway.process(
                adminMessage("gitlab webhook private team/app on gitlab.example.com")
            )
        val privatePayload =
            assertInstanceOf(OperationResult.Success::class.java, private).payload.toString()
        assertTrue(
            privatePayload.contains("https://hooks.example.com/webhooks/gitlab/${self.id}"),
            privatePayload,
        )
        val privateProject = projectRepository.findByInstanceAndFullPath(self, "team/app")!!
        assertNull(privateProject.projectId)
        assertEquals(DeliveryMode.WEBHOOK, privateProject.deliveryMode)
    }
}
