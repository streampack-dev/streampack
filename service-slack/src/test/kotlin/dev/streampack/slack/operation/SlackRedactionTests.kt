/* Joseph B. Ottinger (C)2026 */
package dev.streampack.slack.operation

import dev.streampack.core.integration.IngressLoggingInterceptor
import dev.streampack.slack.service.SlackService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class SlackRedactionTests {
    @Test
    fun `both connect tokens are redacted before the command is logged`() {
        val operation = SlackAdminOperation(Mockito.mock(SlackService::class.java))
        assertEquals(
            "slack connect work [REDACTED] [REDACTED]",
            IngressLoggingInterceptor.redact(
                "slack connect work xoxb-secret xapp-secret",
                operation.redactionRules,
            ),
        )
        assertEquals(
            "slack connect work",
            IngressLoggingInterceptor.redact("slack connect work", operation.redactionRules),
        )
    }
}
