/* Joseph B. Ottinger (C)2026 */
package dev.streampack.mcp.controller

import dev.streampack.test.TestSecurityConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/** The MCP server names itself after the deployment and the site (#233). */
@SpringBootTest(properties = ["streampack.name=gholem", "streampack.blog.site-name=Gholem"])
@AutoConfigureMockMvc(addFilters = false)
@Import(TestSecurityConfiguration::class)
class McpNamingTests {
    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `initialize names the deployment`() {
        mockMvc
            .post("/mcp") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
            }
            .andExpect { jsonPath("$.result.serverInfo.name") { value("gholem-mcp") } }
    }

    @Test
    fun `the plain-text description names the site`() {
        mockMvc.get("/mcp").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.startsWith("Gholem MCP endpoint")) }
        }
    }
}
