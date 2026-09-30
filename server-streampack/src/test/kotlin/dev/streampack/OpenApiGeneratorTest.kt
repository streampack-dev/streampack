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

/** Fetches the OpenAPI spec from the running server and writes it to docs/openapi.json */
@SpringBootTest(
    classes = [ServerStreampackApplication::class],
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

        // Endpoints whose result is a sentence answer a JSON object, not a bare string (#91).
        for ((path, method, status) in
            listOf(
                Triple("/auth/otp/request", "post", "202"),
                Triple("/auth/account", "delete", "200"),
                Triple("/admin/users/{username}/suspend", "put", "200"),
                Triple("/admin/users/{username}/unsuspend", "put", "200"),
                Triple("/admin/users/{username}", "delete", "200"),
                Triple("/admin/users/{username}/purge", "delete", "200"),
            )) {
            val ref =
                root
                    .at("/paths/${path.replace("/", "~1")}/$method/responses/$status/content")
                    .toString()
            check(ref.contains("#/components/schemas/MessageResponse")) {
                "$method $path $status should be a MessageResponse: $ref"
            }
        }

        // A page is a post's ContentDetail, typed like getPostBySlug, so clients get it typed
        // (#90).
        val page = root.at("/paths/~1pages~1{slug}/get")
        check(page.at("/operationId").asText() == "getPage") {
            "GET /pages/{slug} operationId: $page"
        }
        check(page.at("/tags").toString().contains("\"Pages\"")) { "GET /pages/{slug} tag: $page" }
        check(
            page
                .at("/responses/200/content")
                .toString()
                .contains("#/components/schemas/ContentDetail")
        ) {
            "GET /pages/{slug} 200 should be a ContentDetail: ${page.at("/responses/200")}"
        }
        check(
            page
                .at("/responses/404/content")
                .toString()
                .contains("#/components/schemas/ProblemDetail")
        ) {
            "GET /pages/{slug} 404 should be a ProblemDetail: ${page.at("/responses/404")}"
        }

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
