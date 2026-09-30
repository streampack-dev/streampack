/* Joseph B. Ottinger (C)2026 */
package dev.streampack.blog.operation

import dev.streampack.blog.model.DeriveSummaryRequest
import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.messaging.support.MessageBuilder
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional

/** The summary prompt is a setting, so the editorial voice can change without a release (#102). */
@SpringBootTest
@Transactional
@TestPropertySource(properties = ["streampack.blog.summary.system-prompt=Write one dry sentence."])
class DeriveSummaryPromptTests {
    @TestConfiguration
    class Ai {
        @Bean fun aiService() = FakeAiService().also { it.reply = "One dry sentence." }
    }

    @Autowired lateinit var eventGateway: EventGateway
    @Autowired lateinit var ai: FakeAiService

    @Test
    fun `a configured prompt replaces the default`() {
        val admin = UserPrincipal(UUID.randomUUID(), "admin", "Admin", Role.ADMIN)
        val message =
            MessageBuilder.withPayload(DeriveSummaryRequest("Title", "Body text here."))
                .setHeader(
                    Provenance.HEADER,
                    Provenance(
                        protocol = Protocol.HTTP,
                        serviceId = "blog",
                        replyTo = "posts",
                        user = admin,
                    ),
                )
                .build()

        eventGateway.process(message)

        assertEquals("Write one dry sentence.", ai.calls.single().first)
    }
}
