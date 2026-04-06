/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.ai.config.AiProperties
import dev.streampack.ai.service.AiService
import dev.streampack.ai.service.AiStructuredResponse
import dev.streampack.blog.model.DeriveTagsRequest
import dev.streampack.blog.model.DeriveTagsResponse
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.taxonomy.model.TaxonomySnapshot
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.messaging.Message
import org.springframework.messaging.support.MessageBuilder

class DeriveTagsOperationTests {

    @Test
    fun `non-admin is rejected`() {
        val operation = DeriveTagsOperation(StubEventGateway(), providerWithAi(null))
        val request =
            DeriveTagsRequest(
                title = "Java and Loom",
                markdownSource = "Loom improves thread scalability.",
            )

        val result = operation.handle(request, messageWithRole(Role.USER))

        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Insufficient privileges: requires ADMIN", error.message)
    }

    @Test
    fun `blank title returns validation error`() {
        val operation = DeriveTagsOperation(StubEventGateway(), providerWithAi(null))
        val request = DeriveTagsRequest(title = "  ", markdownSource = "Some content")

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Title is required", error.message)
    }

    @Test
    fun `blank content returns validation error`() {
        val operation = DeriveTagsOperation(StubEventGateway(), providerWithAi(null))
        val request = DeriveTagsRequest(title = "A title", markdownSource = "   ")

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Content is required", error.message)
    }

    @Test
    fun `ai unavailable returns error`() {
        val operation = DeriveTagsOperation(StubEventGateway(), providerWithAi(null))
        val request =
            DeriveTagsRequest(
                title = "Java and Loom",
                markdownSource = "Loom improves thread scalability.",
            )

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("AI service unavailable", error.message)
    }

    @Test
    fun `empty AI structured and raw response returns error`() {
        val eventGateway =
            StubEventGateway(
                OperationResult.Success(
                    TaxonomySnapshot(
                        tags = mapOf("java" to 5),
                        categories = emptyMap(),
                        aggregate = mapOf("java" to 5),
                    )
                )
            )
        val operation =
            DeriveTagsOperation(
                eventGateway,
                providerWithAi(StubAiService(structured = null, raw = null)),
            )
        val request =
            DeriveTagsRequest(
                title = "Java and Loom",
                markdownSource = "Loom improves thread scalability.",
            )

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Failed to derive tags: AI returned empty response", error.message)
    }

    @Test
    fun `raw JSON fallback derives significant tags`() {
        val eventGateway =
            StubEventGateway(
                OperationResult.Success(
                    TaxonomySnapshot(
                        tags = mapOf("java" to 10, "loom" to 3),
                        categories = emptyMap(),
                        aggregate = mapOf("java" to 10, "loom" to 3),
                    )
                )
            )
        val operation =
            DeriveTagsOperation(
                eventGateway,
                providerWithAi(
                    StubAiService(structured = null, raw = """{"tags":["java","loom","design"]}""")
                ),
            )
        val request =
            DeriveTagsRequest(
                title = "Java Loom in production",
                markdownSource = "Java Loom and virtual threads. Loom helps Java scale.",
            )

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val success = assertInstanceOf(OperationResult.Success::class.java, result)
        val response = assertInstanceOf(DeriveTagsResponse::class.java, success.payload)
        assertEquals(listOf("java", "loom"), response.tags)
    }

    @Test
    fun `fenced raw JSON is parsed`() {
        val eventGateway =
            StubEventGateway(
                OperationResult.Success(
                    TaxonomySnapshot(
                        tags = mapOf("java" to 10),
                        categories = emptyMap(),
                        aggregate = mapOf("java" to 10),
                    )
                )
            )
        val operation =
            DeriveTagsOperation(
                eventGateway,
                providerWithAi(
                    StubAiService(structured = null, raw = "```json\n{\"tags\":[\"java\"]}\n```")
                ),
            )
        val request =
            DeriveTagsRequest(
                title = "Java update",
                markdownSource = "Java Java Java improvements in this release.",
            )

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val success = assertInstanceOf(OperationResult.Success::class.java, result)
        val response = assertInstanceOf(DeriveTagsResponse::class.java, success.payload)
        assertEquals(listOf("java"), response.tags)
    }

    @Test
    fun `low-confidence candidates are rejected`() {
        val operation =
            DeriveTagsOperation(
                StubEventGateway(OperationResult.NotHandled),
                providerWithAi(
                    StubAiService(structured = null, raw = """{"tags":["design","tools"]}""")
                ),
            )
        val request =
            DeriveTagsRequest(
                title = "Observability with OpenTelemetry",
                markdownSource = "Metrics, traces, and logs with semantic conventions.",
            )

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val error = assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals(
            "Failed to derive tags: suggestions were low-confidence after filtering",
            error.message,
        )
    }

    @Test
    fun `structured response is preferred over raw fallback`() {
        val eventGateway =
            StubEventGateway(
                OperationResult.Success(
                    TaxonomySnapshot(
                        tags = mapOf("java" to 5, "kotlin" to 4),
                        categories = emptyMap(),
                        aggregate = mapOf("java" to 5, "kotlin" to 4),
                    )
                )
            )
        val operation =
            DeriveTagsOperation(
                eventGateway,
                providerWithAi(
                    StubAiService(
                        structured = mapOf("tags" to listOf("java", "kotlin")),
                        raw = """{"tags":["tools","design"]}""",
                    )
                ),
            )
        val request =
            DeriveTagsRequest(
                title = "Kotlin and Java interop",
                markdownSource = "Kotlin and Java work together well for JVM services.",
            )

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val success = assertInstanceOf(OperationResult.Success::class.java, result)
        val response = assertInstanceOf(DeriveTagsResponse::class.java, success.payload)
        assertEquals(listOf("java", "kotlin"), response.tags)
    }

    @Test
    fun `raw comma list fallback parses and normalizes`() {
        val eventGateway =
            StubEventGateway(
                OperationResult.Success(
                    TaxonomySnapshot(
                        tags = mapOf("java" to 5, "state management" to 2),
                        categories = emptyMap(),
                        aggregate = mapOf("java" to 5, "state management" to 2),
                    )
                )
            )
        val operation =
            DeriveTagsOperation(
                eventGateway,
                providerWithAi(
                    StubAiService(structured = null, raw = " #Java, _ignore, state management ")
                ),
            )
        val request =
            DeriveTagsRequest(
                title = "State management in Java",
                markdownSource = "State management in Java can be explicit and predictable.",
            )

        val result = operation.handle(request, messageWithRole(Role.ADMIN))

        val success = assertInstanceOf(OperationResult.Success::class.java, result)
        val response = assertInstanceOf(DeriveTagsResponse::class.java, success.payload)
        assertEquals(listOf("java", "state management"), response.tags)
    }

    private fun messageWithRole(role: Role): Message<DeriveTagsRequest> {
        val user =
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "tester",
                displayName = "Tester",
                role = role,
            )
        return MessageBuilder.withPayload(DeriveTagsRequest("", ""))
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "admin/posts/derive-tags",
                    user = user,
                ),
            )
            .build()
    }

    private fun providerWithAi(aiService: AiService?): ObjectProvider<AiService> {
        val factory = StaticListableBeanFactory()
        if (aiService != null) {
            factory.addBean("aiService", aiService)
        }
        return factory.getBeanProvider(AiService::class.java)
    }

    private class StubEventGateway(
        private val result: OperationResult = OperationResult.NotHandled
    ) : EventGateway {
        override fun process(message: Message<*>): OperationResult = result

        override fun send(message: Message<*>) = Unit
    }

    private class StubAiService(structured: Any?, raw: String?) :
        AiService(mock(org.springframework.ai.chat.model.ChatModel::class.java), AiProperties()) {
        private val structuredValue = structured
        private val rawValue = raw

        override fun <T : Any> promptForObjectWithRaw(
            systemInstruction: String,
            userPrompt: String,
            responseType: Class<T>,
        ): AiStructuredResponse<T> {
            val typedStructured =
                if (structuredValue is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val tags = structuredValue["tags"] as? List<String> ?: emptyList()
                    val ctor = responseType.getDeclaredConstructor(List::class.java)
                    ctor.isAccessible = true
                    ctor.newInstance(tags)
                } else {
                    structuredValue
                }
            @Suppress("UNCHECKED_CAST")
            return AiStructuredResponse(typedStructured as T?, rawValue)
        }
    }
}
