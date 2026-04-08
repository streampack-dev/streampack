/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mcp.controller

import dev.streampack.blog.entity.Post
import dev.streampack.blog.entity.Slug
import dev.streampack.blog.model.PostStatus
import dev.streampack.blog.repository.PostRepository
import dev.streampack.blog.repository.SlugRepository
import dev.streampack.factoid.model.FactoidAttributeType
import dev.streampack.factoid.service.FactoidService
import dev.streampack.test.ResetDatabaseBeforeEach
import dev.streampack.test.TestSecurityConfiguration
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/** Integration tests for the read-only MCP JSON-RPC endpoint. */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ResetDatabaseBeforeEach
@Import(TestSecurityConfiguration::class)
class McpControllerTests {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var factoidService: FactoidService
    @Autowired lateinit var postRepository: PostRepository
    @Autowired lateinit var slugRepository: SlugRepository

    @BeforeEach
    fun setUp() {
        val now = Instant.now()
        val published =
            postRepository.save(
                Post(
                    title = "Visible MCP Post",
                    markdownSource = "This is visible content about MCP tooling.",
                    renderedHtml = "<p>This is visible content about MCP tooling.</p>",
                    excerpt = "Visible MCP content",
                    status = PostStatus.APPROVED,
                    publishedAt = now.minusSeconds(60),
                    createdAt = now.minusSeconds(120),
                    updatedAt = now.minusSeconds(60),
                )
            )
        val draft =
            postRepository.save(
                Post(
                    title = "Hidden Draft Post",
                    markdownSource = "This should never be exposed in public MCP search.",
                    renderedHtml = "<p>This should never be exposed in public MCP search.</p>",
                    excerpt = "Hidden draft content",
                    status = PostStatus.DRAFT,
                    createdAt = now.minusSeconds(90),
                    updatedAt = now.minusSeconds(90),
                )
            )

        slugRepository.save(
            Slug(path = "2026/03/visible-mcp-post", post = published, canonical = true)
        )
        slugRepository.save(
            Slug(path = "2026/03/hidden-draft-post", post = draft, canonical = true)
        )

        factoidService.save(
            "webmention",
            FactoidAttributeType.TEXT,
            "A web standard for cross-site mentions",
            "tester",
        )
        factoidService.save(
            "webmention",
            FactoidAttributeType.URLS,
            "https://www.w3.org/TR/webmention/",
            "tester",
        )
        factoidService.save(
            "webmention",
            FactoidAttributeType.TAGS,
            "web, standards, social",
            "tester",
        )
        factoidService.save(
            "activitypub",
            FactoidAttributeType.TEXT,
            "A federation protocol",
            "tester",
        )
    }

    @Test
    fun `initialize returns server capabilities`() {
        mockMvc
            .post("/mcp") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                    """
                        .trimIndent()
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.result.protocolVersion") { value("2024-11-05") }
                jsonPath("$.result.serverInfo.name") { value("bytecode.news-mcp") }
                jsonPath("$.result.capabilities.tools.listChanged") { value(false) }
            }
    }

    @Test
    fun `tools list includes expected read-only tools`() {
        mockMvc
            .post("/mcp") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                    """
                        .trimIndent()
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.result.tools.length()") { value(7) }
                jsonPath("$.result.tools[0].name") { value("search_posts") }
                jsonPath("$.result.tools[1].name") { value("get_post") }
                jsonPath("$.result.tools[2].name") { value("list_factoids") }
            }
    }

    @Test
    fun `search posts excludes drafts`() {
        mockMvc
            .post("/mcp") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":3,
                      "method":"tools/call",
                      "params":{
                        "name":"search_posts",
                        "arguments":{"query":"hidden","page":0,"size":20}
                      }
                    }
                    """
                        .trimIndent()
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.result.isError") { value(false) }
                jsonPath("$.result.structuredContent.totalCount") { value(0) }
            }
    }

    @Test
    fun `search posts accepts null page and size`() {
        mockMvc
            .post("/mcp") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":31,
                      "method":"tools/call",
                      "params":{
                        "name":"search_posts",
                        "arguments":{"query":"visible","page":null,"size":null}
                      }
                    }
                    """
                        .trimIndent()
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.result.isError") { value(false) }
                jsonPath("$.result.structuredContent.totalCount") { value(1) }
            }
    }

    @Test
    fun `get post returns approved content by slug url`() {
        mockMvc
            .post("/mcp") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":4,
                      "method":"tools/call",
                      "params":{
                        "name":"get_post",
                        "arguments":{"postRef":"https://bytecode.news/posts/2026/03/visible-mcp-post"}
                      }
                    }
                    """
                        .trimIndent()
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.result.isError") { value(false) }
                jsonPath("$.result.structuredContent.title") { value("Visible MCP Post") }
            }
    }

    @Test
    fun `get factoid returns structured metadata`() {
        mockMvc
            .post("/mcp") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":5,
                      "method":"tools/call",
                      "params":{
                        "name":"get_factoid",
                        "arguments":{"selector":"webmention"}
                      }
                    }
                    """
                        .trimIndent()
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.result.isError") { value(false) }
                jsonPath("$.result.structuredContent.selector") { value("webmention") }
                jsonPath("$.result.structuredContent.tags.length()") { value(3) }
                jsonPath("$.result.structuredContent.urls[0]") {
                    value("https://www.w3.org/TR/webmention/")
                }
            }
    }

    @Test
    fun `factoid write reference returns usage examples`() {
        mockMvc
            .post("/mcp") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {
                      "jsonrpc":"2.0",
                      "id":6,
                      "method":"tools/call",
                      "params":{"name":"factoid_write_reference","arguments":{}}
                    }
                    """
                        .trimIndent()
            }
            .andExpect {
                status { isOk() }
                jsonPath("$.result.isError") { value(false) }
                jsonPath("$.result.structuredContent.examples.length()") { value(4) }
            }
    }
}
