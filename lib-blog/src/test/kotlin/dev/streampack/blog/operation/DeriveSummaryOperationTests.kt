/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.DeriveSummaryRequest
import dev.streampack.blog.model.DeriveSummaryResponse
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@Transactional
class DeriveSummaryOperationTests {

    @Autowired lateinit var eventGateway: EventGateway

    private fun message(request: DeriveSummaryRequest, user: UserPrincipal? = null) =
        MessageBuilder.withPayload(request)
            .setHeader(
                Provenance.HEADER,
                Provenance(
                    protocol = Protocol.HTTP,
                    serviceId = "blog-service",
                    replyTo = "posts",
                    user = user,
                ),
            )
            .build()

    @Test
    fun `derive summary returns success for valid content`() {
        val request =
            DeriveSummaryRequest(
                title = "Summary Test",
                markdownSource = "Sentence one. Sentence two. Sentence three. Sentence four.",
            )
        val result = eventGateway.process(message(request))

        assertInstanceOf(OperationResult.Success::class.java, result)
        val response = (result as OperationResult.Success).payload as DeriveSummaryResponse
        assertTrue(response.summary.isNotBlank())
        assertEquals("heuristic", response.source)
    }

    @Test
    fun `without AI, even an admin gets the heuristic, and is told so`() {
        val request =
            DeriveSummaryRequest(title = "Summary Test", markdownSource = "Sentence one. Two.")
        val admin = UserPrincipal(UUID.randomUUID(), "admin", "Admin", Role.ADMIN)
        val result = eventGateway.process(message(request, admin))

        val response = (result as OperationResult.Success).payload as DeriveSummaryResponse
        assertEquals("heuristic", response.source)
        assertTrue(response.summary.isNotBlank())
    }

    @Test
    fun `derive summary with blank title returns error`() {
        val request = DeriveSummaryRequest(title = "", markdownSource = "Sentence.")
        val result = eventGateway.process(message(request))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Title is required", (result as OperationResult.Error).message)
    }

    @Test
    fun `derive summary with blank content returns error`() {
        val request = DeriveSummaryRequest(title = "Title", markdownSource = "")
        val result = eventGateway.process(message(request))

        assertInstanceOf(OperationResult.Error::class.java, result)
        assertEquals("Content is required", (result as OperationResult.Error).message)
    }
}
