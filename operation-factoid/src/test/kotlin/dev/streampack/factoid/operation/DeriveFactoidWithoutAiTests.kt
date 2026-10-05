/* Joseph B. Ottinger (C)2026 */
package dev.streampack.factoid.operation

import dev.streampack.core.integration.EventGateway
import dev.streampack.core.model.OperationResult
import dev.streampack.core.model.Protocol
import dev.streampack.core.model.Provenance
import dev.streampack.core.model.Role
import dev.streampack.core.model.UserPrincipal
import dev.streampack.factoid.model.DeriveFactoidRequest
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.messaging.support.MessageBuilder

/** Without AI configured (as in these tests' context), nothing is drafted at all (#132). */
@SpringBootTest
class DeriveFactoidWithoutAiTests {
    @Autowired lateinit var eventGateway: EventGateway

    @Test
    fun `nothing is derived without AI`() {
        val user =
            UserPrincipal(
                id = UUID.randomUUID(),
                username = "author",
                displayName = "Author",
                role = Role.ADMIN,
            )
        val result =
            eventGateway.process(
                MessageBuilder.withPayload(DeriveFactoidRequest("karaf") as Any)
                    .setHeader(
                        Provenance.HEADER,
                        Provenance(
                            protocol = Protocol.HTTP,
                            serviceId = "factoid",
                            replyTo = "derive",
                            user = user,
                        ),
                    )
                    .build()
            )

        assertEquals(
            DeriveFactoidOperation.AI_UNAVAILABLE,
            (result as OperationResult.Error).message,
        )
    }
}
