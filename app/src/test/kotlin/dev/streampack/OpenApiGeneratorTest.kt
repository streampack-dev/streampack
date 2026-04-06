/* Joseph B. Ottinger (C)2026 */
package dev.streampack

import dev.streampack.core.json.JacksonMappers
import dev.streampack.test.TestChannelConfiguration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/** Fetches the OpenAPI spec from the running app and writes it to docs/openapi.json */
@SpringBootTest(
    classes = [NevetApplication::class],
    properties = ["spring.main.allow-bean-definition-overriding=true"],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@Import(TestChannelConfiguration::class)
class OpenApiGeneratorTest {

    @LocalServerPort private var port: Int = 0

    @Test
    fun `generate OpenAPI spec`() {
        val client = HttpClient.newHttpClient()
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:$port/v3/api-docs"))
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) {
            "Failed to fetch OpenAPI spec: ${response.statusCode()}"
        }

        @Suppress("DEPRECATION") val mapper = JacksonMappers.pretty()
        val root = mapper.readTree(response.body()) as ObjectNode

        // Replace random test port with a stable placeholder
        val servers = mapper.createArrayNode()
        val server = mapper.createObjectNode()
        server.put("url", "http://localhost:8080")
        server.put("description", "Local development server")
        servers.add(server)
        root.set("servers", servers)

        sortKeys(root)

        val docsDir = Path.of("../docs")
        docsDir.toFile().mkdirs()
        docsDir.resolve("openapi.json").toFile().writeText(mapper.writeValueAsString(root))
    }

    /** Recursively sorts all object keys alphabetically for deterministic output */
    private fun sortKeys(node: JsonNode) {
        when (node) {
            is ObjectNode -> {
                node.properties().forEach { (_, value) -> sortKeys(value) }
                val sorted = node.propertyNames().asSequence().sorted().toList()
                val entries = sorted.map { it to node.get(it) }
                node.removeAll()
                entries.forEach { (key, value) -> node.set(key, value) }
            }
            is ArrayNode -> node.forEach { sortKeys(it) }
        }
    }
}
